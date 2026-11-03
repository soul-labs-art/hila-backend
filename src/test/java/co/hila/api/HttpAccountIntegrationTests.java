package co.hila.api;

import static org.junit.jupiter.api.Assertions.*;

import co.hila.api.identity.IdentityService;
import co.hila.api.shared.Database;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"hila.outbox-delay=3600000"})
class HttpAccountIntegrationTests {
  @LocalServerPort int port;
  @Autowired IdentityService identity;
  @Autowired Database db;
  final JsonMapper json = JsonMapper.builder().build();

  @Test
  void actualCookieCsrfLoginAndPublication() throws Exception {
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    var client = HttpClient.newBuilder().cookieHandler(cookies).build();
    String origin = "http://localhost:" + port + "/api/v1";
    var anonymous =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/me")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, anonymous.statusCode());
    var policies =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/policies/current")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, policies.statusCode());
    assertTrue(policies.body().contains("dev-1"));
    var csrf =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String token = json.readTree(csrf.body()).get("token").asText();
    assertTrue(
        java.util.stream.Stream.of(anonymous, policies, csrf)
            .flatMap(response -> response.headers().allValues("set-cookie").stream())
            .anyMatch(
                c ->
                    c.contains("JSESSIONID=")
                        && c.contains("HttpOnly")
                        && c.contains("SameSite=Lax")));
    String email = "http-" + UUID.randomUUID() + "@example.test";
    identity.register("Cuenta HTTP", email, "http-test-password-123", true, "dev-1");
    String encrypted =
        (String)
            db.one(
                    "SELECT payload->>'encryptedToken' AS token FROM email_outbox WHERE"
                        + " recipient_email=?",
                    email)
                .get("token");
    identity.consume(identity.decrypt(encrypted), "EMAIL_VERIFICATION", null);
    String login =
        json.writeValueAsString(Map.of("email", email, "password", "http-test-password-123"));
    var denied =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/auth/sessions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(login))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(403, denied.statusCode());
    assertTrue(denied.body().contains("CSRF_INVALID"));
    var logged =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/auth/sessions"))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(login))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, logged.statusCode(), logged.body());
    assertEquals(email, json.readTree(logged.body()).get("email").asText());
    assertTrue(json.readTree(logged.body()).get("createdAt").isString());
    var me =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/me")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, me.statusCode());
    var renewed =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    String newToken = json.readTree(renewed.body()).get("token").asText();
    String profile =
        "{\"biography\":\"Presentación\",\"profession\":null,\"educationLevel\":null,\"preferredModality\":\"REMOTE\"}";
    var saved =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/me/volunteer-profile"))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", newToken)
                .PUT(HttpRequest.BodyPublishers.ofString(profile))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, saved.statusCode(), saved.body());
    assertTrue(saved.body().contains("PRIVATE"));
    assertEquals("no-store", saved.headers().firstValue("cache-control").orElse(""));
    var logout =
        client.send(
            HttpRequest.newBuilder(URI.create(origin + "/auth/sessions/current"))
                .header("X-CSRF-TOKEN", newToken)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(204, logout.statusCode());
    assertEquals(
        401,
        client
            .send(
                HttpRequest.newBuilder(URI.create(origin + "/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
  }

  @Test
  void exhaustedUniqueLoginTargetReturnsRetryAfterAndNoStore() throws Exception {
    String email = "limited-" + UUID.randomUUID() + "@example.test";
    String bucket = "login-target:" + identity.hash(email);
    try {
      for (int n = 0; n < 10; n++) identity.limit("login-target", email, 10);
      var client =
          HttpClient.newBuilder()
              .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
              .build();
      String origin = "http://localhost:" + port + "/api/v1";
      var csrf =
          client.send(
              HttpRequest.newBuilder(URI.create(origin + "/auth/csrf")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      String token = json.readTree(csrf.body()).get("token").asText();
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create(origin + "/auth/sessions"))
                  .header("Content-Type", "application/json")
                  .header("X-CSRF-TOKEN", token)
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          json.writeValueAsString(
                              Map.of("email", email, "password", "irrelevant-password-123"))))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(429, response.statusCode(), response.body());
      assertEquals("RATE_LIMITED", json.readTree(response.body()).get("code").asText());
      assertTrue(Integer.parseInt(response.headers().firstValue("retry-after").orElseThrow()) > 0);
      assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
    } finally {
      db.jdbc.update("DELETE FROM identity_rate_limits WHERE bucket=?", bucket);
    }
  }
}
