package co.hila.api.opportunities;

import static co.hila.api.shared.MvpStore.*;

import co.hila.api.notifications.NotificationService;
import co.hila.api.notifications.NotificationService.Event;
import co.hila.api.shared.*;
import co.hila.api.shared.MvpRequests.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParticipationService {
  private final Database db;
  private final ApplicationService applications;
  private final NotificationService notifications;

  public ParticipationService(
      Database db, ApplicationService applications, NotificationService notifications) {
    this.db = db;
    this.applications = applications;
    this.notifications = notifications;
  }

  public UUID applicationForParticipation(UUID id) {
    return (UUID)
        db.one("SELECT application_id FROM participation_records WHERE id=?", id)
            .get("application_id");
  }

  private Map<String, Object> participationView(Map<String, Object> row) {
    var data =
        fields(
            row,
            "id",
            "applicationId",
            "actualHours",
            "notes",
            "attendance",
            "recordedBy",
            "verifiedBy",
            "recordedAt",
            "verifiedAt");
    data.put("status", row.get("participation_status"));
    return data;
  }

  private Map<String, Object> participation(UUID application) {
    return db.one("SELECT * FROM participation_records WHERE application_id=?", application);
  }

  public Map<String, Object> getParticipation(UUID actor, UUID application) {
    applications.access(actor, application, false).reader();
    return participationView(participation(application));
  }

  @Transactional
  public Map<String, Object> putParticipation(
      UUID actor, UUID application, ParticipationWrite body) {
    var access = applications.access(actor, application, true);
    access.coordination();
    if (OpportunityService.stamp(access.need(), "ends_at").isAfter(Instant.now()))
      throw transition("Registra la participación después de finalizar la actividad.");
    if ("NO_SHOW".equals(body.attendance())
        && (body.actualHours() == null || body.actualHours().signum() != 0))
      throw ApiFailure.invalidField(
          "actualHours", "OUT_OF_RANGE", "Si la persona no asistió, registra cero horas.");
    var rows =
        db.jdbc.queryForList(
            "SELECT id,participation_status FROM participation_records WHERE application_id=?",
            application);
    if (rows.isEmpty() && !"CONFIRMED".equals(access.application().get("commitment_status")))
      throw transition("Solo se registra participación de un compromiso confirmado.");
    if (!rows.isEmpty() && "VERIFIED".equals(rows.get(0).get("participation_status")))
      throw transition("La participación verificada no se puede modificar.");
    var row =
        db.one(
            """
            INSERT INTO participation_records(application_id,attendance,actual_hours,notes,recorded_by)
            VALUES (?,?,?,?,?) ON CONFLICT(application_id) DO UPDATE SET
              attendance=excluded.attendance,actual_hours=excluded.actual_hours,notes=excluded.notes,
              recorded_by=excluded.recorded_by,recorded_at=now(),
              participation_status='PENDING',verified_by=NULL,verified_at=NULL
            RETURNING *
            """,
            application,
            body.attendance(),
            body.actualHours(),
            text(body.notes()),
            actor);
    db.jdbc.update(
        "UPDATE applications SET commitment_status=?,finished_at=now() WHERE id=?",
        body.attendance(),
        application);
    UUID id = (UUID) row.get("id");
    db.audit(actor, "PARTICIPATION_RECORDED", "PARTICIPATION", id);
    notifications.emit(
        List.of(access.volunteer()),
        Event.PARTICIPATION_RECORDED,
        id,
        "Consulta el registro de participación en Hila.");
    return participationView(row);
  }

  @Transactional
  public Map<String, Object> verifyParticipation(
      UUID actor, UUID id, ParticipationVerification body) {
    UUID application = applicationForParticipation(id);
    var access = applications.access(actor, application, true);
    access.coordination();
    if (access.own()) throw forbidden();
    var row = participation(application);
    if ("VERIFIED".equals(row.get("participation_status")))
      throw transition("La participación ya fue verificada.");
    boolean verified = "VERIFIED".equals(body.decision());
    if (verified && row.get("actual_hours") == null)
      throw ApiFailure.invalid("Registra las horas antes de verificar.");
    if (!verified && (body.reason() == null || body.reason().isBlank()))
      throw ApiFailure.invalidField("reason", "REQUIRED", "Indica el motivo de la disputa.");
    var result =
        db.one(
            """
            UPDATE participation_records SET participation_status=?,verified_by=?,
               verified_at=CASE WHEN ? THEN now() ELSE NULL END WHERE id=? RETURNING *
            """,
            body.decision(),
            verified ? actor : null,
            verified,
            id);
    db.jdbc.update(
        """
        INSERT INTO audit_events(actor_user_id,action,resource_type,resource_id,details)
        VALUES (?,?,'PARTICIPATION',?,jsonb_build_object('reason',?::text))
        """,
        actor,
        verified ? "PARTICIPATION_VERIFIED" : "PARTICIPATION_DISPUTED",
        id,
        text(body.reason()));
    notifications.emit(
        List.of(access.volunteer()),
        Event.PARTICIPATION_VERIFIED,
        id,
        verified
            ? "Tus horas quedaron verificadas."
            : "Hay una disputa en el registro de participación.");
    return participationView(result);
  }

  private Map<String, Object> feedback(Map<String, Object> row) {
    return fields(
        row,
        "id",
        "applicationId",
        "authorUserId",
        "targetUserId",
        "targetOrganizationId",
        "rating",
        "comment",
        "createdAt");
  }

  @Transactional
  public Map<String, Object> createFeedback(UUID actor, UUID application, FeedbackCreate body) {
    var access = applications.access(actor, application, true);
    if (!access.own()) access.coordination();
    var records =
        db.jdbc.queryForList(
            "SELECT participation_status FROM participation_records WHERE application_id=?",
            String.class,
            application);
    if (!List.of("COMPLETED", "NO_SHOW").contains(access.application().get("commitment_status"))
        || !records.contains("VERIFIED"))
      throw transition("La participación debe estar verificada.");
    var created =
        db.jdbc.queryForList(
            """
            INSERT INTO feedback(application_id,author_user_id,target_user_id,target_organization_id,rating,comment)
            VALUES (?,?,?,?,?,?) ON CONFLICT(application_id,author_user_id) DO NOTHING RETURNING *
            """,
            application,
            actor,
            access.own() ? null : access.volunteer(),
            access.own() ? access.organization() : null,
            body.rating(),
            text(body.comment()));
    if (created.isEmpty()) throw transition("Ya enviaste feedback para esta postulación.");
    db.audit(actor, "FEEDBACK_SUBMITTED", "APPLICATION", application);
    return feedback(created.get(0));
  }

  public List<Map<String, Object>> listFeedback(UUID actor, UUID application) {
    var access = applications.access(actor, application, false);
    access.reader();
    var rows =
        access.own()
            ? db.jdbc.queryForList(
                "SELECT * FROM feedback WHERE application_id=? AND author_user_id=? ORDER BY"
                    + " created_at DESC,id DESC",
                application,
                actor)
            : db.jdbc.queryForList(
                "SELECT * FROM feedback WHERE application_id=? ORDER BY created_at DESC,id DESC",
                application);
    return rows.stream().map(this::feedback).toList();
  }
}
