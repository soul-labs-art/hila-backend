package co.hila.api.identity;

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

  public OutboxWorker(
      Database db,
      IdentityService identity,
      JavaMailSender mail,
      @Value("${hila.web-origin}") String origin) {
    this.db = db;
    this.identity = identity;
    this.mail = mail;
    this.origin = origin;
  }

  @Scheduled(fixedDelayString = "${hila.outbox-delay:3000}")
  @Transactional
  public void deliver() {
    for (var row :
        db.jdbc.queryForList(
            "SELECT id,recipient_email,template_key,payload->>'encryptedToken' AS token FROM"
                + " email_outbox WHERE status='PENDING' AND available_at<=now() ORDER BY created_at"
                + " LIMIT 10 FOR UPDATE SKIP LOCKED")) {
      try {
        String token = identity.decrypt((String) row.get("token"));
        String path =
            row.get("template_key").equals("EMAIL_VERIFICATION")
                ? "/verificar-correo"
                : "/restablecer-acceso";
        var message = new SimpleMailMessage();
        message.setFrom("Hila <no-reply@hila.local>");
        message.setTo((String) row.get("recipient_email"));
        message.setSubject(
            path.equals("/verificar-correo")
                ? "Verifica tu correo en Hila"
                : "Recupera tu acceso a Hila");
        message.setText(
            "Abre este enlace para continuar:\n"
                + origin
                + path
                + "#token="
                + token
                + "\nSi no solicitaste este correo, puedes ignorarlo.");
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
}
