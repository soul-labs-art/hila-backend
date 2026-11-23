package co.hila.api.opportunities;

import static co.hila.api.shared.MvpStore.*;

import co.hila.api.notifications.NotificationService;
import co.hila.api.notifications.NotificationService.Event;
import co.hila.api.profiles.ProfileService;
import co.hila.api.shared.*;
import co.hila.api.shared.MvpRequests.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpportunityService {
  private static final String NEEDS =
      """
      SELECT n.*,c.organization_id,c.status AS cause_status,o.verification_status AS organization_status,
          o.name AS organization_name,o.organization_type
      FROM needs n JOIN causes c ON c.id=n.cause_id JOIN organizations o ON o.id=c.organization_id
      """;
  private static final String CAUSES = "SELECT * FROM causes";
  private final Database db;
  private final MvpStore store;
  private final ProfileService profiles;
  private final NotificationService notifications;
  private final CompatibilityService compatibility;

  public OpportunityService(
      MvpStore store,
      ProfileService profiles,
      NotificationService notifications,
      CompatibilityService compatibility) {
    this.db = store.db;
    this.store = store;
    this.profiles = profiles;
    this.notifications = notifications;
    this.compatibility = compatibility;
  }

  private Map<String, Object> cause(UUID id) {
    return db.one(CAUSES + " WHERE id=?", id);
  }

  Map<String, Object> need(UUID id) {
    return db.one(NEEDS + " WHERE n.id=?", id);
  }

  public UUID organizationForNeed(UUID id) {
    return (UUID) need(id).get("organization_id");
  }

  public void requireReader(UUID actor, UUID org) {
    profiles.member(actor, org, false);
  }

  public boolean coordinates(UUID actor, UUID org) {
    return store.count(
            """
            SELECT count(*) FROM organization_memberships m JOIN roles r ON r.id=m.role_id
            JOIN users u ON u.id=m.user_id WHERE m.organization_id=? AND m.user_id=?
              AND m.status='ACTIVE' AND r.active AND r.code IN ('ORG_OWNER','ORG_COORDINATOR')
              AND u.status='ACTIVE' AND u.email_verified_at IS NOT NULL
            """,
            org,
            actor)
        > 0;
  }

  public boolean admin(UUID actor) {
    return store.count(
            """
            SELECT count(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id
            JOIN users u ON u.id=ur.user_id WHERE ur.user_id=? AND r.code='ADMIN' AND r.scope='PLATFORM'
              AND r.active AND u.status='ACTIVE' AND u.email_verified_at IS NOT NULL
            """,
            actor)
        > 0;
  }

  public void requireCoordinator(UUID actor, UUID org) {
    if (!coordinates(actor, org)) {
      requireReader(actor, org);
      throw forbidden();
    }
  }

  private String lockOrganization(UUID org) {
    return (String)
        db.one("SELECT verification_status FROM organizations WHERE id=? FOR UPDATE", org)
            .get("verification_status");
  }

  private void checkCauseApproval(String status, String organizationStatus) {
    if ("ACTIVE".equals(status) && !"APPROVED".equals(organizationStatus))
      throw transition("La organización debe estar aprobada para activar la causa.");
  }

  Map<String, Object> lockNeed(UUID needId) {
    UUID org = organizationForNeed(needId);
    lockOrganization(org);
    db.one("SELECT id FROM needs WHERE id=? FOR UPDATE", needId);
    return need(needId);
  }

  Map<String, Object> lockManagedNeed(UUID actor, UUID needId) {
    var need = lockNeed(needId);
    requireCoordinator(actor, (UUID) need.get("organization_id"));
    return need;
  }

  private Map<String, Object> causeView(Map<String, Object> row) {
    return fields(
        row,
        "id",
        "organizationId",
        "title",
        "summary",
        "description",
        "status",
        "createdBy",
        "createdAt",
        "updatedAt");
  }

  public Map<String, Object> listOrganizationCauses(UUID actor, UUID org, int page, int size) {
    requireReader(actor, org);
    db.one("SELECT id FROM organizations WHERE id=?", org);
    return store.page(
        new Filter(CAUSES).add("organization_id=?", org),
        "created_at DESC,id DESC",
        page,
        size,
        this::causeView);
  }

  @Transactional
  public Map<String, Object> createCause(UUID actor, UUID org, CauseWrite body) {
    String organizationStatus = lockOrganization(org);
    requireCoordinator(actor, org);
    checkCauseApproval(body.status(), organizationStatus);
    if ("ARCHIVED".equals(body.status()))
      throw transition("Una causa nueva no puede empezar archivada.");
    UUID id = UUID.randomUUID();
    db.jdbc.update(
        """
        INSERT INTO causes(id,organization_id,title,summary,description,status,created_by)
        VALUES (?,?,?,?,?,?,?)
        """,
        id,
        org,
        text(body.title()),
        text(body.summary()),
        text(body.description()),
        body.status(),
        actor);
    db.audit(actor, "CAUSE_CREATED", "CAUSE", id);
    return causeView(cause(id));
  }

  public Map<String, Object> getCause(UUID actor, UUID id) {
    var row = cause(id);
    requireReader(actor, (UUID) row.get("organization_id"));
    return causeView(row);
  }

  @Transactional
  public Map<String, Object> putCause(UUID actor, UUID id, CauseWrite body) {
    UUID org = (UUID) cause(id).get("organization_id");
    String organizationStatus = lockOrganization(org);
    requireCoordinator(actor, org);
    checkCauseApproval(body.status(), organizationStatus);
    String from = (String) cause(id).get("status");
    String to = body.status();
    boolean valid =
        from.equals(to)
            || from.equals("DRAFT") && "ACTIVE".equals(to)
            || from.equals("ACTIVE") && "ARCHIVED".equals(to);
    if (!valid || from.equals("ARCHIVED")) throw transition("La causa no admite ese cambio.");
    if ("ARCHIVED".equals(to)
        && store.count("SELECT count(*) FROM needs WHERE cause_id=? AND status='PUBLISHED'", id)
            > 0)
      throw transition("Cierra o cancela las necesidades publicadas antes de archivar la causa.");
    db.jdbc.update(
        "UPDATE causes SET title=?,summary=?,description=?,status=?,updated_at=now() WHERE id=?",
        text(body.title()),
        text(body.summary()),
        text(body.description()),
        to,
        id);
    db.audit(actor, "CAUSE_UPDATED", "CAUSE", id);
    return causeView(cause(id));
  }

  public Map<String, Object> listManagedNeeds(
      UUID actor, UUID causeId, String status, int page, int size) {
    requireReader(actor, (UUID) cause(causeId).get("organization_id"));
    filterValue(status, "DRAFT", "PUBLISHED", "CLOSED", "CANCELLED");
    var filter = new Filter(NEEDS).add("n.cause_id=?", causeId).when("n.status=?", status);
    return store.page(
        filter, "n.created_at DESC,n.id DESC", page, size, row -> needView(row, true));
  }

  public Map<String, Object> listPublicNeeds(
      String q,
      UUID territory,
      String modality,
      UUID skill,
      OffsetDateTime from,
      OffsetDateTime to,
      int page,
      int size) {
    filterValue(modality, "IN_PERSON", "REMOTE", "HYBRID");
    order(from, to);
    var filter =
        new Filter(NEEDS)
            .add("n.status='PUBLISHED' AND c.status='ACTIVE' AND o.verification_status='APPROVED'")
            .search(q, "n.title", "n.summary")
            .when("n.modality=?", modality)
            .when("n.starts_at>=?", from)
            .when("n.starts_at<=?", to)
            .when(
                "EXISTS(SELECT 1 FROM need_skills ns WHERE ns.need_id=n.id AND ns.skill_id=?)",
                skill)
            .when(
                """
                n.territory_id IN (WITH RECURSIVE descendants AS (
                  SELECT id FROM territories WHERE id=? AND active
                  UNION SELECT t.id FROM territories t JOIN descendants d ON d.id=t.parent_id WHERE t.active
                ) SELECT id FROM descendants)
                """,
                territory);
    return store.page(filter, "n.starts_at,n.id", page, size, row -> needView(row, false));
  }

  public Map<String, Object> getPublicNeed(UUID id) {
    var row = need(id);
    if (row.get("published_at") == null || !"APPROVED".equals(row.get("organization_status")))
      throw ApiFailure.missing();
    return needView(row, false);
  }

  public Map<String, Object> getManagedNeed(UUID actor, UUID id) {
    var row = need(id);
    requireReader(actor, (UUID) row.get("organization_id"));
    return needView(row, true);
  }

  long reserved(UUID id) {
    return store.count(
        "SELECT count(*) FROM applications WHERE need_id=? AND commitment_status IN"
            + " ('CONFIRMED','COMPLETED','NO_SHOW')",
        id);
  }

  List<Map<String, Object>> skills(UUID id) {
    return db
        .jdbc
        .queryForList(
            """
            SELECT s.*,ns.required,ns.minimum_proficiency
            FROM need_skills ns JOIN skills s ON s.id=ns.skill_id
            WHERE ns.need_id=? ORDER BY s.name,s.id
            """,
            id)
        .stream()
        .map(
            r ->
                view(
                    "skill",
                    fields(r, "id", "slug", "name", "category", "active"),
                    "required",
                    r.get("required"),
                    "minimumProficiency",
                    r.get("minimum_proficiency")))
        .toList();
  }

  List<Map<String, Object>> resources(UUID id) {
    return db
        .jdbc
        .queryForList(
            """
            SELECT r.*,nr.required,nr.details
            FROM need_resources nr JOIN resources r ON r.id=nr.resource_id
            WHERE nr.need_id=? ORDER BY r.name,r.id
            """,
            id)
        .stream()
        .map(
            r ->
                view(
                    "resource",
                    fields(r, "id", "slug", "name", "category", "active"),
                    "required",
                    r.get("required"),
                    "details",
                    r.get("details")))
        .toList();
  }

  Map<String, Object> needView(Map<String, Object> row, boolean management) {
    UUID id = (UUID) row.get("id");
    var result =
        fields(
            row,
            "id",
            "causeId",
            "title",
            "summary",
            "description",
            "expectedOutcome",
            "modality",
            "riskLevel",
            "startsAt",
            "endsAt",
            "timezone",
            "territoryId",
            "publicLocationLabel",
            "conditions",
            "slotsRequired",
            "status",
            "publishedAt",
            "closedAt",
            "updatedAt");
    result.put(
        "organization",
        view(
            "id",
            row.get("organization_id"),
            "name",
            row.get("organization_name"),
            "organizationType",
            row.get("organization_type")));
    result.put(
        "slotsAvailable",
        Math.max(0, ((Number) row.get("slots_required")).intValue() - reserved(id)));
    result.put("skills", skills(id));
    result.put("resources", resources(id));
    Object status = row.get("outcome_status");
    result.put(
        "outcome",
        status == null
            ? null
            : management
                ? view(
                    "status",
                    status,
                    "summary",
                    row.get("outcome_summary"),
                    "verifiedBy",
                    row.get("outcome_verified_by"),
                    "verifiedAt",
                    row.get("outcome_verified_at"))
                : view("status", status));
    if (management)
      result.putAll(fields(row, "privateLocationInstructions", "createdAt", "createdBy"));
    return result;
  }

  @Transactional
  public Map<String, Object> createNeed(UUID actor, UUID causeId, NeedWrite body) {
    UUID org = (UUID) cause(causeId).get("organization_id");
    lockOrganization(org);
    requireCoordinator(actor, org);
    if ("ARCHIVED".equals(cause(causeId).get("status")))
      throw transition("La causa está archivada.");
    validateNeed(body, null);
    UUID id = UUID.randomUUID();
    Object[] values = needValues(body, id, causeId, actor);
    db.jdbc.update(
        """
        INSERT INTO needs(title,summary,description,expected_outcome,modality,risk_level,starts_at,ends_at,timezone,
            territory_id,public_location_label,conditions,slots_required,private_location_instructions,id,cause_id,created_by)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        values);
    writeReferences(id, body);
    db.audit(actor, "NEED_CREATED", "NEED", id);
    return needView(need(id), true);
  }

  @Transactional
  public Map<String, Object> putManagedNeed(UUID actor, UUID id, NeedWrite body) {
    var current = lockManagedNeed(actor, id);
    boolean published = "PUBLISHED".equals(current.get("status"));
    if (!published && !"DRAFT".equals(current.get("status")))
      throw transition("Esta necesidad ya no se puede editar.");
    if (published && !"APPROVED".equals(current.get("organization_status")))
      throw transition("La organización no está aprobada.");
    validateNeed(body, current);
    if (body.slotsRequired() < reserved(id))
      throw ApiFailure.invalidField(
          "slotsRequired",
          "OUT_OF_RANGE",
          "No puedes reducir los cupos por debajo de los reservados.");
    boolean fixed =
        !stamp(current, "starts_at").equals(body.startsAt().toInstant())
            || !stamp(current, "ends_at").equals(body.endsAt().toInstant())
            || !Objects.equals(body.territoryId(), current.get("territory_id"))
            || !body.modality().equals(current.get("modality"));
    if (fixed
        && store.count(
                "SELECT count(*) FROM applications WHERE need_id=? AND"
                    + " commitment_status='CONFIRMED'",
                id)
            > 0)
      throw transition(
          "Con compromisos confirmados no se puede cambiar el horario, la modalidad ni el"
              + " territorio.");
    if (fixed && published && !body.startsAt().toInstant().isAfter(Instant.now()))
      throw ApiFailure.invalidField(
          "startsAt", "OUT_OF_RANGE", "El nuevo inicio debe estar en el futuro.");
    boolean relevant =
        fixed
            || !Objects.equals(text(body.title()), current.get("title"))
            || !Objects.equals(
                text(body.publicLocationLabel()), current.get("public_location_label"))
            || !Objects.equals(
                text(body.privateLocationInstructions()),
                current.get("private_location_instructions"));
    db.jdbc.update(
        """
        UPDATE needs SET title=?,summary=?,description=?,expected_outcome=?,modality=?,risk_level=?,starts_at=?,
            ends_at=?,timezone=?,territory_id=?,public_location_label=?,conditions=?,slots_required=?,
            private_location_instructions=?,updated_at=now() WHERE id=?
        """,
        needValues(body, id));
    writeReferences(id, body);
    db.audit(actor, "NEED_UPDATED", "NEED", id);
    if (published && relevant) notifyInterested(id, Event.NEED_UPDATED);
    return needView(need(id), true);
  }

  private Object[] needValues(NeedWrite b, Object... rest) {
    List<Object> values =
        new ArrayList<>(
            Arrays.asList(
                text(b.title()),
                text(b.summary()),
                text(b.description()),
                text(b.expectedOutcome()),
                b.modality(),
                b.riskLevel(),
                b.startsAt(),
                b.endsAt(),
                b.timezone(),
                b.territoryId(),
                text(b.publicLocationLabel()),
                text(b.conditions()),
                b.slotsRequired(),
                text(b.privateLocationInstructions())));
    Collections.addAll(values, rest);
    return values.toArray();
  }

  static Instant stamp(Map<String, Object> row, String key) {
    return ((Timestamp) row.get(key)).toInstant();
  }

  private void validateNeed(NeedWrite body, Map<String, Object> previous) {
    if (!body.startsAt().isBefore(body.endsAt()))
      throw ApiFailure.invalidField(
          "endsAt", "OUT_OF_RANGE", "El final debe ser posterior al inicio.");
    if (body.territoryId() == null && !"REMOTE".equals(body.modality()))
      throw ApiFailure.invalidField(
          "territoryId", "REQUIRED", "Indica el territorio de la actividad.");
    if (body.territoryId() != null
        && (previous == null || !body.territoryId().equals(previous.get("territory_id")))
        && store.count("SELECT count(*) FROM territories WHERE id=? AND active", body.territoryId())
            != 1)
      throw ApiFailure.invalidField(
          "territoryId", "INVALID_REFERENCE", "Selecciona un territorio activo.");
    UUID id = previous == null ? null : (UUID) previous.get("id");
    validateReferences(
        body.skills(), NeedSkillInput::skillId, id, "skills", "need_skills", "skill_id");
    validateReferences(
        body.resources(),
        NeedResourceInput::resourceId,
        id,
        "resources",
        "need_resources",
        "resource_id");
  }

  private <T> void validateReferences(
      List<T> items,
      Function<T, UUID> key,
      UUID needId,
      String catalog,
      String link,
      String column) {
    Set<UUID> seen = new HashSet<>();
    for (T item : items) {
      UUID id = key.apply(item);
      if (!seen.add(id)) throw ApiFailure.invalid("La necesidad contiene referencias repetidas.");
      var found =
          db.jdbc.queryForList(
              "SELECT active FROM " + catalog + " WHERE id=? FOR SHARE", Boolean.class, id);
      if (found.isEmpty()
          || !found.get(0)
              && (needId == null
                  || store.count(
                          "SELECT count(*) FROM " + link + " WHERE need_id=? AND " + column + "=?",
                          needId,
                          id)
                      == 0))
        throw ApiFailure.invalid("La necesidad contiene una referencia inexistente o inactiva.");
    }
  }

  private void writeReferences(UUID need, NeedWrite body) {
    db.jdbc.update("DELETE FROM need_skills WHERE need_id=?", need);
    db.jdbc.update("DELETE FROM need_resources WHERE need_id=?", need);
    for (var skill : body.skills())
      db.jdbc.update(
          "INSERT INTO need_skills(need_id,skill_id,required,minimum_proficiency) VALUES (?,?,?,?)",
          need,
          skill.skillId(),
          skill.required(),
          skill.minimumProficiency());
    for (var resource : body.resources())
      db.jdbc.update(
          "INSERT INTO need_resources(need_id,resource_id,required,details) VALUES (?,?,?,?)",
          need,
          resource.resourceId(),
          resource.required(),
          text(resource.details()));
  }

  @Transactional
  public Map<String, Object> publishNeed(UUID actor, UUID id) {
    var row = lockManagedNeed(actor, id);
    if (!"DRAFT".equals(row.get("status"))
        || !"ACTIVE".equals(row.get("cause_status"))
        || !"APPROVED".equals(row.get("organization_status")))
      throw transition("La necesidad aún no se puede publicar.");
    if (!stamp(row, "starts_at").isAfter(Instant.now()))
      throw ApiFailure.invalidField(
          "startsAt", "OUT_OF_RANGE", "El inicio debe estar en el futuro.");
    db.jdbc.update(
        "UPDATE needs SET status='PUBLISHED',published_at=now(),updated_at=now() WHERE id=?", id);
    db.audit(actor, "NEED_PUBLISHED", "NEED", id);
    notifyNewOpportunity(id, row);
    return needView(need(id), true);
  }

  @Transactional
  public Map<String, Object> cancelNeed(UUID actor, UUID id, ReasonRequest body) {
    var row = lockManagedNeed(actor, id);
    if (!List.of("DRAFT", "PUBLISHED").contains(row.get("status")))
      throw transition("La necesidad ya no se puede cancelar.");
    db.jdbc.update(
        """
        UPDATE applications SET commitment_status='CANCELLED',finished_at=now(),cancellation_reason=?
        WHERE need_id=? AND commitment_status='CONFIRMED'
        """,
        text(body.reason()),
        id);
    db.jdbc.update(
        "UPDATE needs SET status='CANCELLED',cancellation_reason=?,updated_at=now() WHERE id=?",
        text(body.reason()),
        id);
    db.audit(actor, "NEED_CANCELLED", "NEED", id);
    notifyInterested(id, Event.NEED_CANCELLED);
    return needView(need(id), true);
  }

  @Transactional
  public Map<String, Object> closeNeed(UUID actor, UUID id, CloseNeedRequest body) {
    var row = lockManagedNeed(actor, id);
    if ("CLOSED".equals(row.get("status"))
        && body.outcomeStatus().equals(row.get("outcome_status"))
        && text(body.summary()).equals(row.get("outcome_summary"))) return needView(row, true);
    if (!"PUBLISHED".equals(row.get("status")) || stamp(row, "ends_at").isAfter(Instant.now()))
      throw transition("Solo se pueden cerrar actividades publicadas que ya terminaron.");
    if (store.count(
            "SELECT count(*) FROM applications WHERE need_id=? AND commitment_status='CONFIRMED'",
            id)
        > 0)
      throw transition("Registra la participación de los compromisos confirmados antes de cerrar.");
    db.jdbc.update(
        """
        UPDATE needs SET status='CLOSED',closed_at=now(),updated_at=now(),outcome_status=?,outcome_summary=?,
            outcome_verified_by=?,outcome_verified_at=now() WHERE id=?
        """,
        body.outcomeStatus(),
        text(body.summary()),
        actor,
        id);
    db.audit(actor, "NEED_CLOSED", "NEED", id);
    notifyInterested(id, Event.NEED_CLOSED);
    return needView(need(id), true);
  }

  private void notifyInterested(UUID id, Event event) {
    notifications.emit(
        notifications.interested(id), event, id, "Consulta los detalles de la actividad en Hila.");
  }

  private void notifyNewOpportunity(UUID id, Map<String, Object> row) {
    var candidates =
        db.jdbc.queryForList(
            """
            SELECT DISTINCT p.user_id FROM volunteer_profiles p JOIN consents c ON c.user_id=p.user_id
            JOIN users u ON u.id=p.user_id
            WHERE p.email_opportunity_alerts AND c.consent_type='OPPORTUNITY_ALERTS' AND c.revoked_at IS NULL
              AND u.status='ACTIVE' AND u.email_verified_at IS NOT NULL
            """,
            UUID.class);
    var team = new HashSet<>(notifications.team((UUID) row.get("organization_id")));
    var recipients =
        candidates.stream()
            .filter(user -> !team.contains(user) && compatibility.basic(user, row))
            .toList();
    notifications.emit(
        recipients, Event.NEW_OPPORTUNITY, id, "Revisa si esta actividad se ajusta a tu perfil.");
  }

  public Map<String, Object> getCompatibility(UUID actor, UUID id) {
    profiles.profile(actor);
    var row = need(id);
    if (!"PUBLISHED".equals(row.get("status"))
        || !"ACTIVE".equals(row.get("cause_status"))
        || !"APPROVED".equals(row.get("organization_status"))) throw ApiFailure.missing();
    return compatibility.evaluate(actor, row, skills(id), resources(id));
  }
}
