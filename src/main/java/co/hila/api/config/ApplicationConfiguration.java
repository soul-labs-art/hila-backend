package co.hila.api.config;

import co.hila.api.shared.Database;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class ApplicationConfiguration {
  @Bean
  ApplicationRunner bootstrap(
      Database db,
      Environment env,
      @Value("${hila.admin-bootstrap.email:}") String email,
      @Value("${hila.outbox-key}") String key) {
    return args -> {
      if (java.util.Arrays.asList(env.getActiveProfiles()).contains("prod")
          && (env.getProperty("hila.policy.version", "dev-1").startsWith("dev")
              || key.length() < 32
              || env.getProperty("hila.policy.content", "Entorno local").startsWith("Entorno local")
              || env.getProperty("hila.policy.alerts-content", "Avisos del entorno")
                  .startsWith("Avisos del entorno")
              || key.equals("development-only-replace-before-real-data")
              || !env.getProperty("hila.storage.provider", "local").equals("r2")))
        throw new IllegalStateException(
            "Production requires approved policy configuration, an encryption key and R2 storage.");
      if (!email.isBlank()) {
        var users =
            db.jdbc.queryForList(
                "SELECT id FROM users WHERE lower(email)=? AND status='ACTIVE'",
                email.trim().toLowerCase(java.util.Locale.ROOT));
        if (users.size() != 1)
          throw new IllegalStateException("Bootstrap requires an existing verified account.");
        db.jdbc.update(
            "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE scope='PLATFORM'"
                + " AND code='ADMIN' ON CONFLICT DO NOTHING",
            users.get(0).get("id"));
        db.audit(
            (java.util.UUID) users.get(0).get("id"),
            "ADMIN_GRANTED",
            "USER",
            (java.util.UUID) users.get(0).get("id"));
      }
    };
  }
}
