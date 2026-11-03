package co.hila.api.profiles;

import co.hila.api.identity.IdentityService;
import co.hila.api.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
  public final Database db;
  final IdentityService identity;

  public ProfileService(Database db, IdentityService identity) {
    this.db = db;
    this.identity = identity;
  }

  public void admin(UUID user) {
    if (!identity.roles(user).contains("ADMIN"))
      throw new ApiFailure(403, "FORBIDDEN", "Acceso exclusivo de administración.");
  }

  public void member(UUID user, UUID org, boolean owner) {
    if (!owner && identity.roles(user).contains("ADMIN")) return;
    int count =
        db.jdbc.queryForObject(
            "SELECT count(*) FROM organization_memberships m JOIN roles r ON r.id=m.role_id WHERE"
                + " m.organization_id=? AND m.user_id=? AND m.status='ACTIVE' AND r.active AND"
                + " (?=false OR r.code='ORG_OWNER')",
            Integer.class,
            org,
            user,
            owner);
    if (count == 0) throw ApiFailure.missing();
  }

  public Map<String, Object> image(UUID user, UUID org, String slot) {
    var rows =
        db.jdbc.queryForList(
            "SELECT id,width,height,content_type FROM profile_images WHERE state='ACTIVE' AND"
                + " slot=? AND "
                + (org == null ? "user_id=?" : "organization_id=?"),
            slot,
            org == null ? user : org);
    if (rows.isEmpty()) return null;
    var r = rows.get(0);
    return Map.of(
        "id",
        r.get("id"),
        "url",
        "/api/v1/profile-images/" + r.get("id"),
        "width",
        r.get("width"),
        "height",
        r.get("height"),
        "contentType",
        r.get("content_type"));
  }

  public Map<String, Object> profile(UUID user) {
    var rows = db.jdbc.queryForList("SELECT * FROM volunteer_profiles WHERE user_id=?", user);
    if (rows.isEmpty())
      throw new ApiFailure(404, "PROFILE_NOT_FOUND", "Completa tu perfil para continuar.");
    var r = rows.get(0);
    Map<String, Object> out = new LinkedHashMap<>();
    String[][] fields = {
      {"userId", "user_id"},
      {"biography", "biography"},
      {"profession", "profession"},
      {"educationLevel", "education_level"},
      {"preferredModality", "preferred_modality"},
      {"emailOpportunityAlerts", "email_opportunity_alerts"},
      {"visibility", "visibility"},
      {"createdAt", "created_at"},
      {"updatedAt", "updated_at"}
    };
    for (var f : fields) out.put(f[0], r.get(f[1]));
    out.put("avatar", image(user, null, "avatar"));
    out.put("cover", image(user, null, "cover"));
    return out;
  }

  @Transactional
  public Map<String, Object> saveProfile(UUID user, ProfileController.ProfileWrite w) {
    if (w.preferredModality() != null
        && !List.of("IN_PERSON", "REMOTE", "HYBRID").contains(w.preferredModality()))
      throw ApiFailure.invalid("Selecciona una modalidad válida.");
    db.jdbc.update(
        "INSERT INTO"
            + " volunteer_profiles(user_id,biography,profession,education_level,preferred_modality)"
            + " VALUES (?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET"
            + " biography=excluded.biography,profession=excluded.profession,education_level=excluded.education_level,preferred_modality=excluded.preferred_modality,updated_at=now()",
        user,
        w.biography(),
        w.profession(),
        w.educationLevel(),
        w.preferredModality());
    return profile(user);
  }

  public Map<String, Object> organization(UUID id) {
    var r = db.one("SELECT * FROM organizations WHERE id=?", id);
    Map<String, Object> out = new LinkedHashMap<>();
    String[][] f = {
      {"id", "id"},
      {"name", "name"},
      {"organizationType", "organization_type"},
      {"legalIdentifier", "legal_identifier"},
      {"description", "description"},
      {"verificationContext", "verification_context"},
      {"publicEmail", "public_email"},
      {"publicWebsite", "public_website"},
      {"serviceTerritoryId", "service_territory_id"},
      {"verificationStatus", "verification_status"},
      {"statusReason", "status_reason"},
      {"createdBy", "created_by"},
      {"createdAt", "created_at"},
      {"updatedAt", "updated_at"},
      {"visibility", "visibility"}
    };
    for (var field : f) out.put(field[0], r.get(field[1]));
    out.put("avatar", image(null, id, "avatar"));
    out.put("cover", image(null, id, "cover"));
    return out;
  }

  void validate(ProfileController.OrganizationWrite w) {
    if (!List.of("FORMAL", "COLLECTIVE").contains(w.organizationType()))
      throw ApiFailure.invalid("Selecciona el tipo de organización.");
    if (w.organizationType().equals("FORMAL")
        && (w.legalIdentifier() == null || w.legalIdentifier().isBlank()))
      throw ApiFailure.invalid("Indica el NIT de la organización formal.");
    if (w.organizationType().equals("COLLECTIVE")
        && (w.verificationContext() == null || w.verificationContext().isBlank()))
      throw ApiFailure.invalid("Describe la trayectoria del colectivo.");
    if (w.publicWebsite() != null && !w.publicWebsite().isBlank()) {
      try {
        var uri = java.net.URI.create(w.publicWebsite());
        if (!List.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null)
          throw new IllegalArgumentException();
      } catch (Exception e) {
        throw ApiFailure.invalid("Usa un sitio web http o https válido.");
      }
    }
    if (w.serviceTerritoryId() != null
        && db.jdbc.queryForObject(
                "SELECT count(*) FROM territories WHERE id=? AND active",
                Integer.class,
                w.serviceTerritoryId())
            != 1) throw ApiFailure.invalid("Selecciona un territorio activo.");
  }

  @Transactional
  public Map<String, Object> createOrg(UUID user, ProfileController.OrganizationWrite w) {
    validate(w);
    UUID id = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO"
            + " organizations(id,name,organization_type,legal_identifier,description,verification_context,public_email,public_website,service_territory_id,created_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        id,
        w.name().trim(),
        w.organizationType(),
        w.legalIdentifier(),
        w.description().trim(),
        w.verificationContext(),
        w.publicEmail(),
        w.publicWebsite(),
        w.serviceTerritoryId(),
        user);
    db.jdbc.update(
        "INSERT INTO organization_memberships(organization_id,user_id,role_id) SELECT ?,?,id FROM"
            + " roles WHERE code='ORG_OWNER' AND scope='ORGANIZATION'",
        id,
        user);
    db.jdbc.update(
        "INSERT INTO organization_verifications(organization_id,decision,reason) VALUES"
            + " (?,'SUBMITTED',?)",
        id,
        w.verificationContext());
    db.audit(user, "ORGANIZATION_CREATED", "ORGANIZATION", id);
    return organization(id);
  }

  @Transactional
  public Map<String, Object> saveOrg(UUID user, UUID id, ProfileController.OrganizationWrite w) {
    member(user, id, true);
    validate(w);
    var old = db.one("SELECT * FROM organizations WHERE id=? FOR UPDATE", id);
    if (old.get("verification_status").equals("APPROVED")
        && (!Objects.equals(old.get("organization_type"), w.organizationType())
            || !Objects.equals(old.get("legal_identifier"), w.legalIdentifier())))
      throw new ApiFailure(
          409, "INVALID_TRANSITION", "No se puede cambiar tipo o NIT después de aprobar.");
    db.jdbc.update(
        "UPDATE organizations SET"
            + " name=?,organization_type=?,legal_identifier=?,description=?,verification_context=?,public_email=?,public_website=?,service_territory_id=?,updated_at=now()"
            + " WHERE id=?",
        w.name().trim(),
        w.organizationType(),
        w.legalIdentifier(),
        w.description().trim(),
        w.verificationContext(),
        w.publicEmail(),
        w.publicWebsite(),
        w.serviceTerritoryId(),
        id);
    db.audit(user, "ORGANIZATION_UPDATED", "ORGANIZATION", id);
    return organization(id);
  }

  public Map<String, Object> page(List<Map<String, Object>> rows, long count, int page, int size) {
    return Map.of(
        "items",
        rows,
        "page",
        page,
        "size",
        size,
        "totalElements",
        count,
        "totalPages",
        (count + size - 1) / size);
  }

  public void paging(int page, int size) {
    if (page < 0 || size < 1 || size > 100) throw ApiFailure.invalid("Paginación fuera de rango.");
  }

  public Map<String, Object> orgList(
      UUID user, String status, int page, int size, boolean administrative) {
    paging(page, size);
    if (administrative) admin(user);
    String where =
        administrative
            ? (status == null ? "true" : "o.verification_status=?")
            : "EXISTS(SELECT 1 FROM organization_memberships m WHERE m.organization_id=o.id AND"
                + " m.user_id=? AND m.status='ACTIVE')";
    Object[] args =
        administrative
            ? (status == null ? new Object[] {} : new Object[] {status})
            : new Object[] {user};
    var ids =
        db.jdbc.queryForList(
            "SELECT o.id FROM organizations o WHERE "
                + where
                + " ORDER BY o.created_at DESC,o.id DESC",
            args);
    var rows =
        ids.stream()
            .skip((long) page * size)
            .limit(size)
            .map(r -> organization((UUID) r.get("id")))
            .toList();
    return page(rows, ids.size(), page, size);
  }

  @Transactional
  public Map<String, Object> publication(UUID user, UUID org, boolean visible) {
    if (org == null) {
      profile(user);
      db.jdbc.update(
          "UPDATE volunteer_profiles SET visibility=?,updated_at=now() WHERE user_id=?",
          visible ? "PUBLIC" : "PRIVATE",
          user);
      db.audit(user, visible ? "PROFILE_PUBLISHED" : "PROFILE_HIDDEN", "USER", user);
      return profile(user);
    }
    member(user, org, true);
    var r = db.one("SELECT verification_status FROM organizations WHERE id=? FOR UPDATE", org);
    if (visible && !r.get("verification_status").equals("APPROVED"))
      throw new ApiFailure(
          409, "INVALID_TRANSITION", "Solo una organización aprobada puede publicarse.");
    db.jdbc.update(
        "UPDATE organizations SET visibility=?,updated_at=now() WHERE id=?",
        visible ? "PUBLIC" : "PRIVATE",
        org);
    db.audit(user, visible ? "PROFILE_PUBLISHED" : "PROFILE_HIDDEN", "ORGANIZATION", org);
    return organization(org);
  }

  public Map<String, Object> publicVolunteer(UUID id) {
    db.one(
        "SELECT u.id FROM users u JOIN volunteer_profiles v ON u.id=v.user_id WHERE u.id=? AND"
            + " u.status='ACTIVE' AND v.visibility='PUBLIC'",
        id);
    var p = profile(id);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", id);
    out.put(
        "displayName", db.one("SELECT display_name FROM users WHERE id=?", id).get("display_name"));
    for (String key : List.of("biography", "profession", "preferredModality", "avatar", "cover"))
      out.put(key, p.get(key));
    return out;
  }

  public Map<String, Object> publicOrg(UUID id) {
    db.one(
        "SELECT id FROM organizations WHERE id=? AND visibility='PUBLIC' AND"
            + " verification_status='APPROVED'",
        id);
    var p = organization(id);
    Map<String, Object> out = new LinkedHashMap<>();
    for (String k :
        List.of(
            "id",
            "name",
            "organizationType",
            "description",
            "publicEmail",
            "publicWebsite",
            "verificationStatus",
            "avatar",
            "cover")) out.put(k, p.get(k));
    UUID t = (UUID) p.get("serviceTerritoryId");
    out.put("serviceTerritory", t == null ? null : territory(t));
    return out;
  }

  public Map<String, Object> territory(UUID id) {
    return db.one(
        "SELECT id,slug,name,territory_type AS type,parent_id AS \"parentId\",active FROM"
            + " territories WHERE id=?",
        id);
  }

  @Transactional
  public Map<String, Object> review(UUID user, UUID id, ProfileController.Review w) {
    admin(user);
    var org = db.one("SELECT verification_status FROM organizations WHERE id=? FOR UPDATE", id);
    String old = (String) org.get("verification_status");
    boolean valid =
        old.equals("PENDING") && List.of("APPROVED", "REJECTED", "MORE_INFO").contains(w.decision())
            || old.equals("APPROVED") && w.decision().equals("SUSPENDED")
            || old.equals("SUSPENDED") && w.decision().equals("APPROVED");
    if (!valid)
      throw new ApiFailure(
          409, "INVALID_TRANSITION", "Esta decisión no corresponde al estado actual.");
    String state = w.decision().equals("MORE_INFO") ? "PENDING" : w.decision();
    db.jdbc.update(
        "UPDATE organizations SET verification_status=?,status_reason=?,visibility=CASE WHEN"
            + " ?='SUSPENDED' THEN 'PRIVATE' ELSE visibility END,updated_at=now() WHERE id=?",
        state,
        w.reason(),
        state,
        id);
    UUID review = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO organization_verifications(id,organization_id,reviewed_by,decision,reason)"
            + " VALUES (?,?,?,?,?)",
        review,
        id,
        user,
        w.decision(),
        w.reason());
    db.audit(user, "ORGANIZATION_" + w.decision(), "ORGANIZATION", id);
    return db.one(
        "SELECT id,organization_id AS \"organizationId\",reviewed_by AS"
            + " \"reviewedBy\",decision,reason,decided_at AS \"decidedAt\" FROM"
            + " organization_verifications WHERE id=?",
        review);
  }

  @Transactional
  public Map<String, Object> resubmit(UUID user, UUID org, String context) {
    member(user, org, true);
    var row = db.one("SELECT verification_status FROM organizations WHERE id=? FOR UPDATE", org);
    String state = (String) row.get("verification_status");
    var latest =
        db.jdbc.queryForList(
            "SELECT decision FROM organization_verifications WHERE organization_id=? ORDER BY"
                + " decided_at DESC LIMIT 1",
            org);
    if (!state.equals("REJECTED")
        && !(state.equals("PENDING")
            && !latest.isEmpty()
            && latest.get(0).get("decision").equals("MORE_INFO")))
      throw new ApiFailure(
          409, "INVALID_TRANSITION", "La organización no admite reenvío en este estado.");
    db.jdbc.update(
        "UPDATE organizations SET"
            + " verification_status='PENDING',verification_context=?,updated_at=now() WHERE id=?",
        context,
        org);
    UUID id = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO organization_verifications(id,organization_id,decision,reason) VALUES"
            + " (?,?,'SUBMITTED',?)",
        id,
        org,
        context);
    return db.one(
        "SELECT id,organization_id AS \"organizationId\",reviewed_by AS"
            + " \"reviewedBy\",decision,reason,decided_at AS \"decidedAt\" FROM"
            + " organization_verifications WHERE id=?",
        id);
  }

  @Transactional
  public Map<String, Object> alerts(UUID user, ProfileController.Alerts w) {
    profile(user);
    if (w.enabled()) identity.policy(w.policyVersion());
    else if (db.jdbc.queryForObject(
            "SELECT count(*) FROM consents WHERE user_id=? AND consent_type='OPPORTUNITY_ALERTS'"
                + " AND document_version=?",
            Integer.class,
            user,
            w.policyVersion())
        == 0) throw ApiFailure.invalid("No existe esa aceptación de avisos.");
    if (w.enabled())
      db.jdbc.update(
          "INSERT INTO consents(user_id,consent_type,document_version) VALUES"
              + " (?,'OPPORTUNITY_ALERTS',?) ON CONFLICT(user_id,consent_type,document_version) DO"
              + " UPDATE SET accepted_at=now(),revoked_at=NULL",
          user,
          w.policyVersion());
    else
      db.jdbc.update(
          "UPDATE consents SET revoked_at=now() WHERE user_id=? AND"
              + " consent_type='OPPORTUNITY_ALERTS' AND document_version=?",
          user,
          w.policyVersion());
    db.jdbc.update(
        "UPDATE volunteer_profiles SET email_opportunity_alerts=?,updated_at=now() WHERE user_id=?",
        w.enabled(),
        user);
    db.audit(user, "OPPORTUNITY_ALERTS_UPDATED", "USER", user);
    return Map.of(
        "enabled",
        w.enabled(),
        "policyVersion",
        w.policyVersion(),
        "updatedAt",
        java.time.Instant.now().toString());
  }

  public Map<String, Object> history(UUID actor, UUID id, int page, int size) {
    member(actor, id, false);
    paging(page, size);
    var rows =
        db.jdbc.queryForList(
            "SELECT id,organization_id AS \"organizationId\",reviewed_by AS"
                + " \"reviewedBy\",decision,reason,decided_at AS \"decidedAt\" FROM"
                + " organization_verifications WHERE organization_id=? ORDER BY decided_at DESC,id"
                + " DESC",
            id);
    return page(
        rows.stream().skip((long) page * size).limit(size).toList(), rows.size(), page, size);
  }

  public Map<String, Object> memberships(UUID actor, UUID id, int page, int size) {
    member(actor, id, false);
    paging(page, size);
    var rows =
        db.jdbc.queryForList(
            "SELECT m.organization_id AS \"organizationId\",m.user_id AS \"userId\",u.display_name"
                + " AS \"displayName\",r.code AS role,m.status,m.created_at AS \"createdAt\" FROM"
                + " organization_memberships m JOIN users u ON m.user_id=u.id JOIN roles r ON"
                + " r.id=m.role_id WHERE m.organization_id=? ORDER BY m.created_at DESC,m.user_id"
                + " DESC",
            id);
    return page(
        rows.stream().skip((long) page * size).limit(size).toList(), rows.size(), page, size);
  }

  public Map<String, Object> territories(int page, int size, UUID parentId, String type, String q) {
    paging(page, size);
    var rows =
        db
            .jdbc
            .queryForList(
                "SELECT id,slug,name,territory_type AS type,parent_id AS \"parentId\",active FROM"
                    + " territories WHERE active ORDER BY name,id")
            .stream()
            .filter(r -> parentId == null || parentId.equals(r.get("parentId")))
            .filter(r -> type == null || type.equals(r.get("type")))
            .filter(
                r ->
                    q == null
                        || r.get("name")
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .contains(q.toLowerCase(Locale.ROOT)))
            .toList();
    return page(
        rows.stream().skip((long) page * size).limit(size).toList(), rows.size(), page, size);
  }
}
