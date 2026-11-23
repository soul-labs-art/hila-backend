package co.hila.api.notifications;

import static co.hila.api.shared.MvpStore.*;

import co.hila.api.shared.*;
import co.hila.api.shared.MvpRequests.NotificationRead;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  private final Database db;
  private final MvpStore store;

  public NotificationService(MvpStore store) {
    this.store = store;
    this.db = store.db;
  }

  public enum Event {
    APPLICATION_RECEIVED("Recibiste una postulación", "APPLICATION", true),
    APPLICATION_DECIDED("Respondieron a tu postulación", "APPLICATION", true),
    COMMITMENT_CONFIRMED("Se confirmó un compromiso", "APPLICATION", true),
    COMMITMENT_CANCELLED("Se canceló un compromiso", "APPLICATION", true),
    NEED_UPDATED("Cambió una actividad", "NEED", true),
    NEED_CANCELLED("Se canceló una actividad", "NEED", true),
    NEED_CLOSED("Se cerró una actividad", "NEED", true),
    PARTICIPATION_RECORDED("Registraron tu participación", "PARTICIPATION", false),
    PARTICIPATION_VERIFIED("Revisaron tu participación", "PARTICIPATION", false),
    ORGANIZATION_REVIEWED("Revisaron una organización", "ORGANIZATION", true),
    NEW_OPPORTUNITY("Hay una oportunidad que podría interesarte", "NEED", true);

    public final String title;
    public final String resource;
    public final boolean email;

    Event(String title, String resource, boolean email) {
      this.title = title;
      this.resource = resource;
      this.email = email;
    }
  }

  public List<UUID> team(UUID organization) {
    return db.jdbc.queryForList(
        """
        SELECT m.user_id FROM organization_memberships m
        JOIN roles r ON r.id=m.role_id JOIN users u ON u.id=m.user_id
        WHERE m.organization_id=? AND m.status='ACTIVE' AND r.active
          AND r.code IN ('ORG_OWNER','ORG_COORDINATOR') AND u.status='ACTIVE'
          AND u.email_verified_at IS NOT NULL
        """,
        UUID.class,
        organization);
  }

  public List<UUID> interested(UUID need) {
    return db.jdbc.queryForList(
        """
        SELECT volunteer_id FROM applications WHERE need_id=? AND status IN ('APPLIED','ACCEPTED')
        """,
        UUID.class,
        need);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void emit(Collection<UUID> users, Event event, UUID resource, String body) {
    for (UUID user : new LinkedHashSet<>(users)) {
      db.jdbc.update(
          """
          INSERT INTO notifications(user_id,notification_type,title,body,resource_type,resource_id)
          SELECT id,?,?,?,?,? FROM users WHERE id=? AND status='ACTIVE' AND email_verified_at IS NOT NULL
          """,
          event.name(),
          event.title,
          body,
          event.resource,
          resource,
          user);
      if (event.email) {
        db.jdbc.update(
            """
            INSERT INTO email_outbox(recipient_email,template_key,payload)
            SELECT email,?,jsonb_build_object('userId',?::text,'resourceId',?::text)
            FROM users WHERE id=? AND status='ACTIVE' AND email_verified_at IS NOT NULL
            """,
            event.name(),
            user,
            resource,
            user);
      }
    }
  }

  private Map<String, Object> viewNotification(Map<String, Object> row) {
    var result =
        fields(row, "id", "title", "body", "resourceType", "resourceId", "createdAt", "readAt");
    result.put("type", row.get("notification_type"));
    return result;
  }

  public Map<String, Object> listMyNotifications(
      UUID actor, Boolean unreadOnly, int page, int size) {
    var filter = new Filter("SELECT * FROM notifications").add("user_id=?", actor);
    if (Boolean.TRUE.equals(unreadOnly)) filter.add("read_at IS NULL");
    return store.page(filter, "created_at DESC,id DESC", page, size, this::viewNotification);
  }

  public Map<String, Object> getUnreadNotificationCount(UUID actor) {
    return view(
        "count",
        store.count(
            "SELECT count(*) FROM notifications WHERE user_id=? AND read_at IS NULL", actor));
  }

  public Map<String, Object> readNotification(UUID actor, UUID id, NotificationRead body) {
    return viewNotification(
        db.one(
            """
            UPDATE notifications SET read_at=coalesce(read_at,now())
            WHERE user_id=? AND id=? RETURNING *
            """,
            actor,
            id));
  }
}
