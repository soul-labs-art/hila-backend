package co.hila.api.opportunities;

import static co.hila.api.shared.MvpStore.*;

import co.hila.api.notifications.NotificationService;
import co.hila.api.notifications.NotificationService.Event;
import co.hila.api.profiles.ProfileService;
import co.hila.api.shared.*;
import co.hila.api.shared.MvpRequests.*;
import co.hila.api.volunteer.CapacityService;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {
  private static final String APPLICATIONS = "SELECT * FROM applications";
  private final Database db;
  private final MvpStore store;
  private final OpportunityService opportunities;
  private final ProfileService profiles;
  private final CapacityService capacities;
  private final NotificationService notifications;

  public ApplicationService(
      MvpStore store,
      OpportunityService opportunities,
      ProfileService profiles,
      CapacityService capacities,
      NotificationService notifications) {
    this.db = store.db;
    this.store = store;
    this.opportunities = opportunities;
    this.profiles = profiles;
    this.capacities = capacities;
    this.notifications = notifications;
  }

  public record Access(
      Map<String, Object> application,
      Map<String, Object> need,
      UUID volunteer,
      UUID organization,
      boolean own,
      boolean coordinator,
      boolean admin) {
    public void reader() {
      if (!own && !coordinator && !admin) throw ApiFailure.missing();
    }

    public void owner() {
      if (!own) {
        reader();
        throw forbidden();
      }
    }

    public void coordination() {
      if (!coordinator) {
        reader();
        throw forbidden();
      }
    }
  }

  Map<String, Object> application(UUID id) {
    return db.one(APPLICATIONS + " WHERE id=?", id);
  }

  public Access access(UUID actor, UUID id, boolean write) {
    var row = application(id);
    UUID needId = (UUID) row.get("need_id");
    var need = write ? opportunities.lockNeed(needId) : opportunities.need(needId);
    if (write) row = application(id);
    UUID volunteer = (UUID) row.get("volunteer_id");
    UUID org = (UUID) need.get("organization_id");
    return new Access(
        row,
        need,
        volunteer,
        org,
        volunteer.equals(actor),
        opportunities.coordinates(actor, org),
        opportunities.admin(actor));
  }

  Map<String, Object> applicationView(Map<String, Object> row) {
    return fields(
        row,
        "id",
        "needId",
        "volunteerId",
        "status",
        "commitmentStatus",
        "message",
        "decisionReason",
        "appliedAt",
        "decidedAt",
        "confirmedAt",
        "finishedAt",
        "cancellationReason");
  }

  @Transactional
  public Map<String, Object> createApplication(UUID actor, UUID needId, ApplicationCreate body) {
    var need = opportunities.lockNeed(needId);
    if (need.get("published_at") == null || !"APPROVED".equals(need.get("organization_status")))
      throw ApiFailure.missing();
    profiles.profile(actor);
    requireOpen(need);
    requireFuture(need);
    UUID id = UUID.randomUUID();
    if (db.jdbc.update(
            """
            INSERT INTO applications(id,need_id,volunteer_id,message) VALUES (?,?,?,?)
            ON CONFLICT(need_id,volunteer_id) DO NOTHING
            """,
            id,
            needId,
            actor,
            text(body.message()))
        != 1) throw conflict("APPLICATION_EXISTS", "Ya te postulaste a esta actividad.");
    db.audit(actor, "APPLICATION_SUBMITTED", "APPLICATION", id);
    notifications.emit(
        notifications.team((UUID) need.get("organization_id")),
        Event.APPLICATION_RECEIVED,
        id,
        "Hay una nueva postulación para revisar en Hila.");
    return applicationView(application(id));
  }

  private void requireOpen(Map<String, Object> need) {
    if (!"PUBLISHED".equals(need.get("status"))
        || !"ACTIVE".equals(need.get("cause_status"))
        || !"APPROVED".equals(need.get("organization_status")))
      throw transition("La actividad ya no recibe postulaciones ni compromisos.");
  }

  private void requireFuture(Map<String, Object> need) {
    if (!OpportunityService.stamp(need, "starts_at").isAfter(Instant.now()))
      throw transition("La actividad ya comenzó.");
  }

  public Map<String, Object> listNeedApplications(
      UUID actor, UUID need, String status, String commitment, int page, int size) {
    UUID org = opportunities.organizationForNeed(need);
    opportunities.requireCoordinator(actor, org);
    var filter = filters(status, commitment).add("need_id=?", need);
    return store.page(
        filter,
        "applied_at DESC,id DESC",
        page,
        size,
        row -> {
          var result = applicationView(row);
          result.put("applicant", applicant((UUID) row.get("volunteer_id")));
          return result;
        });
  }

  private Map<String, Object> applicant(UUID user) {
    var row =
        db.one(
            """
            SELECT u.display_name,v.biography,v.profession,v.education_level,v.preferred_modality
            FROM users u JOIN volunteer_profiles v ON v.user_id=u.id WHERE u.id=?
            """,
            user);
    return view(
        "userId",
        user,
        "displayName",
        row.get("display_name"),
        "profile",
        fields(row, "biography", "profession", "educationLevel", "preferredModality"),
        "skills",
        capacities.getMySkills(user),
        "availability",
        capacities.getMyAvailability(user),
        "serviceAreas",
        capacities.getMyServiceAreas(user),
        "resources",
        capacities.getMyResources(user));
  }

  private Filter filters(String status, String commitment) {
    filterValue(status, "APPLIED", "ACCEPTED", "REJECTED", "WITHDRAWN");
    filterValue(commitment, "NOT_CONFIRMED", "CONFIRMED", "COMPLETED", "NO_SHOW", "CANCELLED");
    return new Filter(APPLICATIONS)
        .when("status=?", status)
        .when("commitment_status=?", commitment);
  }

  public Map<String, Object> listMyApplications(
      UUID actor, String status, String commitment, int page, int size) {
    return store.page(
        filters(status, commitment).add("volunteer_id=?", actor),
        "applied_at DESC,id DESC",
        page,
        size,
        this::applicationView);
  }

  public Map<String, Object> getApplication(UUID actor, UUID id) {
    var access = access(actor, id, false);
    access.reader();
    return applicationView(access.application());
  }

  @Transactional
  public Map<String, Object> decideApplication(UUID actor, UUID id, ApplicationDecision body) {
    var access = access(actor, id, true);
    access.coordination();
    Object current = access.application().get("status");
    if (body.decision().equals(current)) return applicationView(access.application());
    if (!"APPLIED".equals(current)) throw transition("La postulación ya tiene otra decisión.");
    requireOpen(access.need());
    db.jdbc.update(
        "UPDATE applications SET status=?,decision_reason=?,decided_at=now() WHERE id=?",
        body.decision(),
        text(body.reason()),
        id);
    db.audit(actor, "APPLICATION_" + body.decision(), "APPLICATION", id);
    notifications.emit(
        List.of(access.volunteer()),
        Event.APPLICATION_DECIDED,
        id,
        "Consulta la decisión de la organización en Hila.");
    return applicationView(application(id));
  }

  @Transactional
  public Map<String, Object> withdrawApplication(UUID actor, UUID id) {
    var access = access(actor, id, true);
    access.owner();
    if (!List.of("APPLIED", "ACCEPTED").contains(access.application().get("status"))
        || !"NOT_CONFIRMED".equals(access.application().get("commitment_status")))
      throw transition("Esta postulación ya no se puede retirar.");
    db.jdbc.update("UPDATE applications SET status='WITHDRAWN',finished_at=now() WHERE id=?", id);
    db.audit(actor, "APPLICATION_WITHDRAWN", "APPLICATION", id);
    return applicationView(application(id));
  }

  @Transactional
  public Map<String, Object> confirmCommitment(UUID actor, UUID id) {
    var access = access(actor, id, true);
    access.owner();
    if (!"ACCEPTED".equals(access.application().get("status")))
      throw transition("La postulación no está aceptada.");
    Object current = access.application().get("commitment_status");
    if ("CONFIRMED".equals(current)) return applicationView(access.application());
    if (!"NOT_CONFIRMED".equals(current))
      throw transition("Este compromiso ya no se puede confirmar.");
    requireOpen(access.need());
    requireFuture(access.need());
    UUID needId = (UUID) access.need().get("id");
    if (opportunities.reserved(needId)
        >= ((Number) access.need().get("slots_required")).longValue())
      throw conflict("NO_SLOTS_AVAILABLE", "Los cupos de esta actividad ya se completaron.");
    db.jdbc.update(
        "UPDATE applications SET commitment_status='CONFIRMED',confirmed_at=now(),confirmed_by=?"
            + " WHERE id=?",
        actor,
        id);
    db.audit(actor, "COMMITMENT_CONFIRMED", "APPLICATION", id);
    notifications.emit(
        notifications.team(access.organization()),
        Event.COMMITMENT_CONFIRMED,
        id,
        "Revisa el compromiso confirmado en Hila.");
    return applicationView(application(id));
  }

  @Transactional
  public Map<String, Object> cancelCommitment(
      UUID actor, UUID id, CommitmentCancellationRequest body) {
    var access = access(actor, id, true);
    if (!access.own()) access.coordination();
    if (!"CONFIRMED".equals(access.application().get("commitment_status")))
      throw transition("Solo se puede cancelar un compromiso confirmado.");
    db.jdbc.update(
        "UPDATE applications SET"
            + " commitment_status='CANCELLED',finished_at=now(),cancellation_reason=? WHERE id=?",
        text(body.reason()),
        id);
    db.audit(actor, "COMMITMENT_CANCELLED", "APPLICATION", id);
    var recipients =
        access.own()
            ? notifications.team(access.organization()).stream()
                .filter(u -> !actor.equals(u))
                .toList()
            : List.of(access.volunteer());
    notifications.emit(
        recipients, Event.COMMITMENT_CANCELLED, id, "Consulta el estado del compromiso en Hila.");
    return applicationView(application(id));
  }

  public Map<String, Object> getActivityInstructions(UUID actor, UUID id) {
    var access = access(actor, id, false);
    access.reader();
    if (!access.coordinator()
        && !access.admin()
        && !List.of("CONFIRMED", "COMPLETED")
            .contains(access.application().get("commitment_status"))) throw forbidden();
    return view(
        "needId",
        access.need().get("id"),
        "instructions",
        access.need().get("private_location_instructions"));
  }
}
