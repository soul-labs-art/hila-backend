package co.hila.api.admin;

import static co.hila.api.shared.MvpStore.*;

import co.hila.api.identity.AccountSessions;
import co.hila.api.profiles.ProfileService;
import co.hila.api.shared.*;
import co.hila.api.shared.MvpRequests.AccountStatusUpdate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministrationService {
  private final MvpStore store;
  private final ProfileService profiles;
  private final Database db;

  public AdministrationService(MvpStore store, ProfileService profiles) {
    this.store = store;
    this.profiles = profiles;
    this.db = store.db;
  }

  private Map<String, Object> user(Map<String, Object> row) {
    return fields(row, "id", "email", "displayName", "status", "createdAt");
  }

  public Map<String, Object> adminListUsers(
      UUID actor, String q, String status, int page, int size) {
    profiles.admin(actor);
    filterValue(status, "PENDING_EMAIL", "ACTIVE", "SUSPENDED");
    var filter =
        new Filter("SELECT id,email,display_name,status,created_at FROM users")
            .when("status=?", status)
            .search(q, "display_name", "email");
    return store.page(filter, "created_at DESC,id DESC", page, size, this::user);
  }

  @Transactional
  public Map<String, Object> adminUpdateUserStatus(UUID actor, UUID id, AccountStatusUpdate body) {
    db.one("SELECT id FROM roles WHERE scope='PLATFORM' AND code='ADMIN' FOR UPDATE");
    profiles.admin(actor);
    var target = db.one("SELECT id,status,email_verified_at FROM users WHERE id=? FOR UPDATE", id);
    if (actor.equals(id)) throw forbidden();
    String current = (String) target.get("status");
    if ("PENDING_EMAIL".equals(current)
        || target.get("email_verified_at") == null
        || current.equals(body.status()))
      throw transition("La cuenta no admite el cambio solicitado.");
    boolean suspend = "SUSPENDED".equals(body.status());
    if (suspend) {
      long otherAdmins =
          store.count(
              """
              SELECT count(*) FROM users u JOIN user_roles ur ON ur.user_id=u.id
              JOIN roles r ON r.id=ur.role_id
              WHERE u.id<>? AND u.status='ACTIVE' AND u.email_verified_at IS NOT NULL
                AND r.scope='PLATFORM' AND r.code='ADMIN' AND r.active
              """,
              id);
      if (otherAdmins == 0)
        throw conflict("LAST_ADMIN_REQUIRED", "Debe quedar una cuenta administradora activa.");
    }
    db.jdbc.update("UPDATE users SET status=?,updated_at=now() WHERE id=?", body.status(), id);
    if (suspend) AccountSessions.revoke(db, id);
    db.jdbc.update(
        """
        INSERT INTO audit_events(actor_user_id,action,resource_type,resource_id,details)
        VALUES (?,?,'USER',?, jsonb_build_object('reason',?::text))
        """,
        actor,
        suspend ? "ACCOUNT_SUSPENDED" : "ACCOUNT_REINSTATED",
        id,
        text(body.reason()));
    return user(db.one("SELECT id,email,display_name,status,created_at FROM users WHERE id=?", id));
  }

  public Map<String, Object> adminListAuditEvents(
      UUID actor,
      UUID actorUserId,
      UUID resourceId,
      String resourceType,
      String action,
      OffsetDateTime from,
      OffsetDateTime to,
      int page,
      int size) {
    profiles.admin(actor);
    order(from, to);
    var filter =
        new Filter(
                "SELECT id,actor_user_id,action,resource_type,resource_id,occurred_at FROM"
                    + " audit_events")
            .when("actor_user_id=?", actorUserId)
            .when("resource_id=?", resourceId)
            .when("resource_type=?", resourceType)
            .when("action=?", action)
            .when("occurred_at>=?", from)
            .when("occurred_at<=?", to);
    return store.page(
        filter,
        "occurred_at DESC,id DESC",
        page,
        size,
        row ->
            fields(row, "id", "actorUserId", "action", "resourceType", "resourceId", "occurredAt"));
  }
}
