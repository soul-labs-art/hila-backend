package co.hila.api.config;

import co.hila.api.profiles.MembershipService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnNotWebApplication
public class OwnershipRecovery {
  @Bean
  @ConditionalOnProperty(name = "hila.owner-recovery.organization")
  ApplicationRunner recoverOwner(
      MembershipService memberships,
      @Value("${hila.owner-recovery.actor}") UUID actor,
      @Value("${hila.owner-recovery.organization}") UUID organization,
      @Value("${hila.owner-recovery.user}") UUID user) {
    return args -> {
      memberships.recoverOwner(actor, organization, user);
      org.slf4j.LoggerFactory.getLogger(OwnershipRecovery.class)
          .info("Organization ownership recovered.");
    };
  }
}
