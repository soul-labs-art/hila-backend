package co.hila.api.identity;

import co.hila.api.notifications.NotificationService.Event;
import co.hila.api.shared.Database;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxWorker {
  final Database db;
  final IdentityService identity;
  final JavaMailSender mail;
  final String origin;
  final String from;

  public OutboxWorker(
      Database db,
      IdentityService identity,
      JavaMailSender mail,
      @Value("${hila.web-origin}") String origin,
      @Value("${hila.mail.from}") String from) {
    this.db = db;
    this.identity = identity;
    this.mail = mail;
    this.origin = origin.replaceAll("/+$", "");
    this.from = from;
  }

  @Scheduled(fixedDelayString = "${hila.outbox-delay:3000}")
  @Transactional
  public void deliver() {
    for (var row :
        db.jdbc.queryForList(
            """
            SELECT id,recipient_email,template_key,payload->>'encryptedToken' AS token,
                   payload->>'userId' AS recipient_id,payload->>'resourceId' AS resource_id
            FROM email_outbox WHERE status='PENDING' AND available_at<=now()
            ORDER BY created_at,id LIMIT 10 FOR UPDATE SKIP LOCKED
            """)) {
      try {
        var message = message(row);
        if (message == null) {
          db.jdbc.update(
              "UPDATE email_outbox SET status='FAILED',payload='{}',last_error='Delivery"
                  + " suppressed' WHERE id=?",
              row.get("id"));
          continue;
        }
        mail.send(message);
        db.jdbc.update(
            "UPDATE email_outbox SET"
                + " status='SENT',sent_at=now(),payload='{}'::jsonb,last_error=NULL WHERE id=?",
            row.get("id"));
      } catch (Exception e) {
        db.jdbc.update(
            "UPDATE email_outbox SET attempt_count=attempt_count+1,status=CASE WHEN"
                + " attempt_count>=4 THEN 'FAILED' ELSE 'PENDING' END,available_at=now()+interval"
                + " '1 minute'*(attempt_count+1),last_error='Delivery failed' WHERE id=?",
            row.get("id"));
      }
    }
  }

  private SimpleMailMessage message(Map<String, Object> row) {
    String template = (String) row.get("template_key");
    var message = new SimpleMailMessage();
    message.setFrom(from);
    if ("EMAIL_VERIFICATION".equals(template) || "PASSWORD_RESET".equals(template)) {
      String token = identity.decrypt((String) row.get("token"));
      boolean verify = "EMAIL_VERIFICATION".equals(template);
      message.setTo((String) row.get("recipient_email"));
      message.setSubject(verify ? "Verifica tu correo en Hila" : "Recupera tu acceso a Hila");
      message.setText(
          "Abre este enlace para continuar:\n"
              + origin
              + (verify ? "/verificar-correo" : "/restablecer-acceso")
              + "#token="
              + token
              + "\nSi no solicitaste este correo, puedes ignorarlo.");
      return message;
    }
    Event event = Event.valueOf(template);
    UUID user = UUID.fromString((String) row.get("recipient_id"));
    var recipients =
        db.jdbc.queryForList(
            """
            SELECT email FROM users WHERE id=? AND status='ACTIVE' AND email_verified_at IS NOT NULL
            """,
            String.class,
            user);
    if (recipients.isEmpty()) return null;
    if (event == Event.NEW_OPPORTUNITY
        && db.jdbc.queryForObject(
                """
                SELECT count(*) FROM volunteer_profiles p JOIN consents c ON c.user_id=p.user_id
                JOIN needs n ON n.id=? JOIN causes a ON a.id=n.cause_id JOIN organizations o ON o.id=a.organization_id
                WHERE p.user_id=? AND p.email_opportunity_alerts AND c.consent_type='OPPORTUNITY_ALERTS'
                  AND c.revoked_at IS NULL AND n.status='PUBLISHED' AND o.verification_status='APPROVED'
                  AND n.starts_at>now()
                """,
                Long.class,
                UUID.fromString((String) row.get("resource_id")),
                user)
            == 0) return null;
    message.setTo(recipients.get(0));
    message.setSubject(event.title);
    message.setText(
        event.title + ". Entra a Hila para consultar los detalles:\n" + origin + "/ingresar");
    return message;
  }
}
