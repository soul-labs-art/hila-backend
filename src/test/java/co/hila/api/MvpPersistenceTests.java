package co.hila.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.hila.api.identity.IdentityService;
import co.hila.api.identity.OutboxWorker;
import co.hila.api.notifications.NotificationService;
import co.hila.api.notifications.NotificationService.Event;
import co.hila.api.shared.Database;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "hila.outbox-delay=3600000")
class MvpPersistenceTests {
  @Autowired DataSource source;
  @Autowired Database db;
  @Autowired IdentityService identity;
  @Autowired NotificationService notifications;

  @Test
  void legacyParticipationSurvivesUpgrade() {
    String schema = "mvp_" + UUID.randomUUID().toString().replace("-", "");
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .target("3")
        .load()
        .migrate();
    try {
      var user = UUID.randomUUID();
      var organization = UUID.randomUUID();
      var cause = UUID.randomUUID();
      var need = UUID.randomUUID();
      var application = UUID.randomUUID();
      db.jdbc.update(
          "INSERT INTO "
              + schema
              + ".users(id,email,password_hash,display_name,adult_confirmed_at) VALUES"
              + " (?,?,?,?,now())",
          user,
          "migration@example.test",
          "unused-fixture",
          "Cuenta de migración");
      db.jdbc.update("INSERT INTO " + schema + ".volunteer_profiles(user_id) VALUES (?)", user);
      db.jdbc.update(
          "INSERT INTO "
              + schema
              + ".organizations(id,name,organization_type,created_by) VALUES"
              + " (?,'Organización','FORMAL',?)",
          organization,
          user);
      db.jdbc.update(
          "INSERT INTO "
              + schema
              + ".causes(id,organization_id,title,summary,created_by) VALUES"
              + " (?,?,'Causa','Resumen',?)",
          cause,
          organization,
          user);
      db.jdbc.update(
          "INSERT INTO "
              + schema
              + ".needs(id,cause_id,title,summary,description,expected_outcome,modality,risk_level,"
              + "starts_at,ends_at,slots_required,created_by) VALUES"
              + " (?,?,'Actividad','Resumen','Descripción','Resultado','REMOTE','LOW',now(),now()+interval"
              + " '1 hour',1,?)",
          need,
          cause,
          user);
      db.jdbc.update(
          "INSERT INTO "
              + schema
              + ".applications(id,need_id,volunteer_id,commitment_status,confirmed_by,confirmed_at)"
              + " VALUES (?,?,?,'NO_SHOW',?,now())",
          application,
          need,
          user,
          user);
      db.jdbc.update(
          "INSERT INTO "
              + schema
              + ".participation_records(application_id,actual_hours,recorded_by) VALUES (?,0,?)",
          application,
          user);

      Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
      assertEquals(
          "NO_SHOW",
          db.one(
                  "SELECT attendance FROM "
                      + schema
                      + ".participation_records WHERE application_id=?",
                  application)
              .get("attendance"));
      assertEquals(
          0L,
          db.one("SELECT session_version FROM " + schema + ".users WHERE id=?", user)
              .get("session_version"));
    } finally {
      db.jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  @Test
  @Transactional
  void smtpFailureRetriesAndRevokedOpportunityConsentSuppressesQueuedMail() {
    db.jdbc.update("UPDATE email_outbox SET available_at=now()+interval '1 day'");
    UUID user = UUID.randomUUID(), organization = UUID.randomUUID();
    String email = "outbox-" + user + "@example.test";
    db.jdbc.update(
        """
        INSERT INTO users(id,email,password_hash,display_name,status,adult_confirmed_at,email_verified_at)
        VALUES (?,?,'unused-fixture','Cuenta de correo','ACTIVE',now(),now())
        """,
        user,
        email);
    var mail = mock(JavaMailSender.class);
    doThrow(new MailSendException("SMTP de prueba"))
        .doNothing()
        .when(mail)
        .send(any(SimpleMailMessage.class));
    var worker =
        new OutboxWorker(
            db, identity, mail, "http://localhost:5173", "Hila <no-reply@example.test>");
    notifications.emit(
        List.of(user), Event.ORGANIZATION_REVIEWED, organization, "Revisión de prueba");
    worker.deliver();
    var retry =
        db.one("SELECT status,attempt_count FROM email_outbox WHERE recipient_email=?", email);
    assertEquals("PENDING", retry.get("status"));
    assertEquals(1, ((Number) retry.get("attempt_count")).intValue());
    db.jdbc.update("UPDATE email_outbox SET available_at=now() WHERE recipient_email=?", email);
    worker.deliver();
    assertEquals(
        "SENT",
        db.one("SELECT status FROM email_outbox WHERE recipient_email=?", email).get("status"));

    var cause = UUID.randomUUID();
    var need = UUID.randomUUID();
    db.jdbc.update(
        "INSERT INTO organizations(id,name,organization_type,verification_status,created_by)"
            + " VALUES (?,'Organización','FORMAL','APPROVED',?)",
        organization,
        user);
    db.jdbc.update(
        "INSERT INTO causes(id,organization_id,title,summary,status,created_by)"
            + " VALUES (?,?,'Causa','Resumen','ACTIVE',?)",
        cause,
        organization,
        user);
    db.jdbc.update(
        """
        INSERT INTO needs(id,cause_id,title,summary,description,expected_outcome,status,modality,risk_level,
            starts_at,ends_at,slots_required,published_at,created_by)
        VALUES (?,?,'Actividad','Resumen','Descripción','Resultado','PUBLISHED','REMOTE','LOW',
            now()+interval '1 hour',now()+interval '2 hours',1,now(),?)
        """,
        need,
        cause,
        user);
    db.jdbc.update(
        "INSERT INTO volunteer_profiles(user_id,email_opportunity_alerts) VALUES (?,true)", user);
    db.jdbc.update(
        "INSERT INTO consents(user_id,consent_type,document_version) VALUES"
            + " (?,'OPPORTUNITY_ALERTS','dev-1')",
        user);
    notifications.emit(List.of(user), Event.NEW_OPPORTUNITY, need, "Oportunidad de prueba");
    db.jdbc.update(
        "UPDATE consents SET revoked_at=now() WHERE user_id=? AND"
            + " consent_type='OPPORTUNITY_ALERTS'",
        user);
    worker.deliver();
    assertEquals(
        "Delivery suppressed",
        db.one(
                """
                SELECT last_error FROM email_outbox WHERE recipient_email=? AND template_key='NEW_OPPORTUNITY'
                """,
                email)
            .get("last_error"));
    verify(mail, times(2)).send(any(SimpleMailMessage.class));
  }
}
