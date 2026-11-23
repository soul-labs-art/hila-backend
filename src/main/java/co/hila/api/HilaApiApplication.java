package co.hila.api;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HilaApiApplication {

  public static void main(String[] args) {
    var application = new SpringApplication(HilaApiApplication.class);
    boolean recoverOwner =
        Arrays.stream(args).anyMatch(arg -> arg.startsWith("--hila.owner-recovery.organization="));
    if (recoverOwner) {
      application.setWebApplicationType(WebApplicationType.NONE);
      application.run(args).close();
    } else {
      application.run(args);
    }
  }
}
