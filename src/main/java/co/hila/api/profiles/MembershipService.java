package co.hila.api.profiles;

import static co.hila.api.shared.MvpStore.*;

import co.hila.api.identity.IdentityService;
import co.hila.api.shared.*;
import co.hila.api.shared.MvpRequests.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipService {
  private static final String OWNER = "ORG_OWNER";
  private final ProfileService profiles;
  private final IdentityService identity;
  private final Database db;
  private final MvpStore store;

  public MembershipService(ProfileService profiles, IdentityService identity, MvpStore store) {
    this.profiles = profiles;
    this.identity = identity;
    this.store = store;
    this.db = store.db;
  }

  private void lockOrganization(UUID org) {
    db.one("SELECT id FROM organizations WHERE id=? FOR UPDATE", org);
  }

  private long owners(UUID org) {
    return store.count(
        """
        SELECT count(*) FROM organization_memberships m JOIN roles r ON r.id=m.role_id
        JOIN users u ON u.id=m.user_id
        WHERE m.organization_id=? AND m.status='ACTIVE' AND r.code='ORG_OWNER' AND r.active
          AND u.status='ACTIVE' AND u.email_verified_at IS NOT NULL
        """,
        org);
  }

  private Map<String, Object> entry(UUID org, UUID user) {
    return db.one(
        """
        SELECT m.organization_id AS "organizationId",m.user_id AS "userId",u.display_name AS "displayName",
               r.code AS role,m.status,m.created_at AS "createdAt"
        FROM organization_memberships m JOIN roles r ON r.id=m.role_id JOIN users u ON u.id=m.user_id
        WHERE m.organization_id=? AND m.user_id=?
        """,
        org,
        user);
  }

  @Transactional
  public Map<String, Object> createMembership(UUID actor, UUID org, MembershipCreate body) {
    lockOrganization(org);
    profiles.member(actor, org, true);
    var users =
        db.jdbc.queryForList(
            """
            SELECT id FROM users WHERE lower(email)=? AND status='ACTIVE' AND email_verified_at IS NOT NULL
            FOR SHARE
            """,
            UUID.class,
            identity.normalize(body.email()));
    if (users.isEmpty())
      throw new ApiFailure(422, "MEMBER_UNAVAILABLE", "No se pudo incorporar esa cuenta.");
    UUID user = users.get(0);
    int changed =
        db.jdbc.update(
            """
            INSERT INTO organization_memberships(organization_id,user_id,role_id)
              SELECT ?,?,id FROM roles WHERE scope='ORGANIZATION' AND code=? AND active
            ON CONFLICT DO NOTHING
            """,
            org,
            user,
            body.role());
    if (changed != 1)
      throw conflict("MEMBERSHIP_EXISTS", "La cuenta ya tiene membresía en esta organización.");
    db.audit(actor, "MEMBERSHIP_CREATED", "ORGANIZATION", org);
    return entry(org, user);
  }

  @Transactional
  public Map<String, Object> updateMembership(
      UUID actor, UUID org, UUID user, MembershipUpdate body) {
    lockOrganization(org);
    profiles.member(actor, org, true);
    var current = entry(org, user);
    var target = db.one("SELECT status,email_verified_at FROM users WHERE id=? FOR SHARE", user);
    boolean active =
        "ACTIVE".equals(target.get("status")) && target.get("email_verified_at") != null;
    boolean activation = "ACTIVE".equals(body.status());
    if (active
        && OWNER.equals(current.get("role"))
        && "ACTIVE".equals(current.get("status"))
        && (!OWNER.equals(body.role()) || !activation)
        && owners(org) <= 1)
      throw conflict("LAST_OWNER_REQUIRED", "Debe quedar un propietario activo.");
    if (activation && !active)
      throw new ApiFailure(422, "MEMBER_UNAVAILABLE", "La cuenta no está habilitada.");
    db.jdbc.update(
        """
        UPDATE organization_memberships SET role_id=(SELECT id FROM roles WHERE code=? AND scope='ORGANIZATION'),
           status=? WHERE organization_id=? AND user_id=?
        """,
        body.role(),
        body.status(),
        org,
        user);
    db.audit(actor, "MEMBERSHIP_UPDATED", "ORGANIZATION", org);
    return entry(org, user);
  }

  @Transactional
  public void recoverOwner(UUID admin, UUID org, UUID newOwner) {
    identity.active(admin.toString());
    profiles.admin(admin);
    lockOrganization(org);
    if (owners(org) > 0) throw transition("La organización ya tiene propietario activo.");
    db.one(
        "SELECT id FROM users WHERE id=? AND status='ACTIVE' AND email_verified_at IS NOT NULL FOR"
            + " SHARE",
        newOwner);
    db.jdbc.update(
        """
        INSERT INTO organization_memberships(organization_id,user_id,role_id,status)
          SELECT ?,?,id,'ACTIVE' FROM roles WHERE code='ORG_OWNER' AND scope='ORGANIZATION' AND active
        ON CONFLICT(organization_id,user_id) DO UPDATE SET role_id=excluded.role_id,status='ACTIVE'
        """,
        org,
        newOwner);
    db.jdbc.update(
        """
        INSERT INTO audit_events(actor_user_id,action,resource_type,resource_id,details)
        VALUES (?,'ORGANIZATION_OWNER_RECOVERED','ORGANIZATION',?,jsonb_build_object('ownerUserId',?::text))
        """,
        admin,
        org,
        newOwner);
  }
}
