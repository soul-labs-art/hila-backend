package co.hila.api;

import static co.hila.api.shared.MvpStore.view;
import static org.junit.jupiter.api.Assertions.*;

import co.hila.api.identity.IdentityService;
import co.hila.api.profiles.MembershipService;
import co.hila.api.shared.Database;
import io.swagger.v3.oas.annotations.Operation;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "hila.outbox-delay=3600000")
class MvpHttpIntegrationTests {
  private static final String PASSWORD = "test-password-for-hila-123";
  private static final byte[] BOUNDARY =
      "hila-fixture-boundary".getBytes(StandardCharsets.US_ASCII);
  private final JsonMapper json = JsonMapper.builder().build();
  private final Set<String> successfulOperations = ConcurrentHashMap.newKeySet();
  private final JsonNode contract = json.readTree(Path.of("openapi/hila-v1.json").toFile());

  @LocalServerPort int port;
  @Autowired Database db;
  @Autowired IdentityService identity;
  @Autowired MembershipService memberships;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  @Test
  void everyProductRouteAgreesWithSpringMvc() {
    Set<String> documented = new TreeSet<>();
    contract
        .path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        operation ->
                            documented.add(
                                operation.getKey().toUpperCase(Locale.ROOT)
                                    + " "
                                    + normalized(path.getKey()))));
    Set<String> mapped = new TreeSet<>();
    mappings
        .getHandlerMethods()
        .forEach(
            (mapping, handler) -> {
              for (String path : mapping.getPatternValues()) {
                if (path.startsWith("/api/v1/")
                    && !handler.hasMethodAnnotation(io.swagger.v3.oas.annotations.Hidden.class)) {
                  mapping
                      .getMethodsCondition()
                      .getMethods()
                      .forEach(
                          method ->
                              mapped.add(
                                  method.name()
                                      + " "
                                      + normalized(path.substring("/api/v1".length()))));
                }
              }
            });
    assertEquals(documented, mapped);
  }

  private String normalized(String path) {
    return path.replaceAll("\\{[^}]+}", "{}");
  }

  private String routePattern(String pattern) {
    return pattern.replaceAll("\\{[^}]+}", "[^/]+");
  }

  @Test
  void productOperationsAndAccessControlAgainstPostgres() throws Exception {
    db.jdbc.update("DELETE FROM identity_rate_limits WHERE bucket LIKE 'login-ip:%'");
    Client anon = new Client();
    Client admin = account(true),
        owner = account(false),
        first = account(false),
        second = account(false);
    Client outsider = account(false);
    String suffix = UUID.randomUUID().toString();

    var skillBody =
        view(
            "slug",
            "skill-" + suffix,
            "name",
            "Habilidad " + suffix,
            "category",
            "Cuidado",
            "active",
            true);
    String skill = id(admin.post("/admin/catalogs/skills", skillBody));
    admin.put("/admin/catalogs/skills/" + skill, skillBody);
    admin.get("/admin/catalogs/skills?active=true");
    anon.get("/catalogs/skills?category=Cuidado&q=" + suffix);

    var resourceBody =
        view(
            "slug",
            "resource-" + suffix,
            "name",
            "Recurso " + suffix,
            "category",
            "Equipo",
            "active",
            true);
    String resource = id(admin.post("/admin/catalogs/resources", resourceBody));
    admin.put("/admin/catalogs/resources/" + resource, resourceBody);
    admin.get("/admin/catalogs/resources?active=true");
    anon.get("/catalogs/resources?q=" + suffix);

    var territoryBody =
        view(
            "slug",
            "territory-" + suffix,
            "name",
            "Territorio " + suffix,
            "type",
            "VALLEY",
            "parentId",
            null,
            "active",
            true);
    String territory = id(admin.post("/admin/catalogs/territories", territoryBody));
    admin.put("/admin/catalogs/territories/" + territory, territoryBody);
    admin.get("/admin/catalogs/territories?active=true");
    anon.get("/catalogs/territories?q=" + suffix);
    assertEquals("FORBIDDEN", outsider.error("GET", "/admin/users", null, 403));

    String org =
        id(
            owner.post(
                "/organizations",
                view(
                    "name",
                    "Colectivo " + suffix,
                    "organizationType",
                    "COLLECTIVE",
                    "legalIdentifier",
                    null,
                    "description",
                    "Descripción",
                    "verificationContext",
                    "Contexto comunitario",
                    "publicEmail",
                    null,
                    "publicWebsite",
                    null,
                    "serviceTerritoryId",
                    territory)));
    String causes = "/organizations/" + org + "/causes";
    var causeBody =
        view("title", "Causa", "summary", "Resumen", "description", null, "status", "DRAFT");
    String pendingCause = id(owner.post(causes, causeBody));
    causeBody.put("status", "ACTIVE");
    assertEquals("INVALID_TRANSITION", owner.error("POST", causes, causeBody, 409));
    assertEquals(
        "INVALID_TRANSITION", owner.error("PUT", "/causes/" + pendingCause, causeBody, 409));
    admin.post(
        "/admin/organizations/" + org + "/reviews",
        view("decision", "APPROVED", "reason", "Revisión de prueba"));
    owner.put("/causes/" + pendingCause, causeBody);
    String team = "/organizations/" + org + "/memberships";
    assertEquals(
        "LAST_OWNER_REQUIRED",
        owner.error(
            "PATCH",
            team + "/" + owner.id,
            view("role", "ORG_COORDINATOR", "status", "ACTIVE"),
            409));
    owner.post(team, view("email", second.email, "role", "ORG_COORDINATOR"));
    owner.patch(team + "/" + second.id, view("role", "ORG_COORDINATOR", "status", "REVOKED"));
    owner.get(team);

    var profileBody =
        view(
            "biography",
            "Perfil de prueba",
            "profession",
            null,
            "educationLevel",
            null,
            "preferredModality",
            "REMOTE");
    first.put("/me/volunteer-profile", profileBody);
    second.put("/me/volunteer-profile", profileBody);
    outsider.put("/me/volunteer-profile", profileBody);
    first.put(
        "/me/skills",
        view(
            "items",
            List.of(view("skillId", skill, "proficiency", "ADVANCED", "yearsExperience", 3))));
    first.get("/me/skills");
    first.put(
        "/me/availability",
        view(
            "timezone",
            "America/Bogota",
            "items",
            List.of(view("weekday", 1, "startsAt", "08:00", "endsAt", "11:00"))));
    first.get("/me/availability");
    first.put("/me/service-areas", view("territoryIds", List.of(territory)));
    first.get("/me/service-areas");
    first.put(
        "/me/resources",
        view("items", List.of(view("resourceId", resource, "details", "Equipo disponible"))));
    first.get("/me/resources");
    first.put("/me/opportunity-alerts", view("enabled", true, "policyVersion", "dev-1"));

    String cause =
        id(
            owner.post(
                "/organizations/" + org + "/causes",
                view(
                    "title",
                    "Causa",
                    "summary",
                    "Resumen",
                    "description",
                    null,
                    "status",
                    "ACTIVE")));
    assertEquals("RESOURCE_NOT_FOUND", outsider.error("GET", "/causes/" + cause, null, 404));
    owner.get("/organizations/" + org + "/causes");
    owner.get("/causes/" + cause);
    owner.put(
        "/causes/" + cause,
        view("title", "Causa", "summary", "Actualizada", "description", null, "status", "ACTIVE"));

    Instant start = Instant.now().plusSeconds(86_400);
    var needBody =
        view(
            "title",
            "Actividad " + suffix,
            "summary",
            "Resumen",
            "description",
            "Actividad de prueba",
            "expectedOutcome",
            "Resultado esperado",
            "modality",
            "REMOTE",
            "riskLevel",
            "LOW",
            "startsAt",
            start.toString(),
            "endsAt",
            start.plusSeconds(3600).toString(),
            "timezone",
            "America/Bogota",
            "territoryId",
            null,
            "publicLocationLabel",
            null,
            "conditions",
            null,
            "slotsRequired",
            1,
            "privateLocationInstructions",
            "Instrucciones privadas",
            "skills",
            List.of(view("skillId", skill, "required", true, "minimumProficiency", "BASIC")),
            "resources",
            List.of(view("resourceId", resource, "required", false, "details", null)));
    String needId = id(owner.post("/causes/" + cause + "/needs", needBody));
    String need = "/needs/" + needId;
    String draft = id(owner.post("/causes/" + cause + "/needs", needBody));
    assertEquals("RESOURCE_NOT_FOUND", anon.error("GET", need, null, 404));
    owner.get("/causes/" + cause + "/needs?status=DRAFT");
    owner.get(need + "/management");
    owner.put(need + "/management", needBody);
    owner.post(need + "/publication", null);
    owner.post("/needs/" + draft + "/cancellation", view("reason", "Borrador cancelado"));
    assertEquals(
        1L,
        anon.get("/needs?q=" + suffix + "&skillId=" + skill + "&startsFrom=" + Instant.now())
            .path("totalElements")
            .asLong());
    assertFalse(anon.get(need).has("privateLocationInstructions"));
    assertEquals(5, first.get(need + "/compatibility").path("criteria").size());
    assertEquals(1, first.get("/me/notifications?unreadOnly=true").path("items").size());
    assertEquals(1, first.get("/me/notifications/unread-count").path("count").asInt());
    String notice = id(first.get("/me/notifications").path("items").get(0));
    assertEquals(
        "RESOURCE_NOT_FOUND",
        outsider.error("PATCH", "/me/notifications/" + notice, view("read", true), 404));
    var readNotice = first.patch("/me/notifications/" + notice, view("read", true));
    assertEquals(readNotice, first.patch("/me/notifications/" + notice, view("read", true)));
    first.put(
        "/me/opportunity-alerts",
        view("enabled", false, "policyVersion", "new-version-without-acceptance"));
    assertNotNull(first.get("/me/consents").get(0));

    String one = "/applications/" + id(first.post(need + "/applications", view("message", null)));
    String two =
        "/applications/" + id(second.post(need + "/applications", view("message", "Me postulo")));
    String retract =
        "/applications/" + id(outsider.post(need + "/applications", view("message", null)));
    outsider.post(retract + "/withdrawal", null);
    assertEquals(
        "APPLICATION_EXISTS",
        outsider.error("POST", need + "/applications", view("message", null), 409));
    assertEquals("RESOURCE_NOT_FOUND", outsider.error("GET", one, null, 404));
    assertEquals("RESOURCE_NOT_FOUND", outsider.error("GET", need + "/applications", null, 404));
    assertEquals("FORBIDDEN", first.error("GET", one + "/activity-instructions", null, 403));
    first.get("/me/applications?status=APPLIED");
    first.get(one);
    assertEquals("FORBIDDEN", admin.error("GET", need + "/applications", null, 403));
    owner.get(need + "/applications?commitmentStatus=NOT_CONFIRMED");
    var accept = view("decision", "ACCEPTED", "reason", null);
    owner.put(one + "/decision", accept);
    owner.put(two + "/decision", accept);

    var pool = Executors.newFixedThreadPool(2);
    try {
      var firstResult = pool.submit(() -> first.send("PUT", one + "/commitment", null, null));
      var secondResult = pool.submit(() -> second.send("PUT", two + "/commitment", null, null));
      int firstStatus = firstResult.get(10, TimeUnit.SECONDS).statusCode();
      int secondStatus = secondResult.get(10, TimeUnit.SECONDS).statusCode();
      assertTrue(
          firstStatus == 200 && secondStatus == 409 || firstStatus == 409 && secondStatus == 200,
          "Expected one 200 and one 409, got " + firstStatus + " and " + secondStatus);
      boolean firstWon = firstStatus == 200;
      Client winner = firstWon ? first : second;
      Client runnerUp = firstWon ? second : first;
      String winning = firstWon ? one : two;
      String pending = firstWon ? two : one;
      assertEquals("NO_SLOTS_AVAILABLE", runnerUp.error("PUT", pending + "/commitment", null, 409));
      var commitment = winner.put(winning + "/commitment", null);
      assertEquals(commitment, winner.put(winning + "/commitment", null));
      assertEquals(
          "Instrucciones privadas",
          winner.get(winning + "/activity-instructions").path("instructions").asText());
      winner.post(winning + "/commitment/cancellation", view("reason", null));
      runnerUp.put(pending + "/commitment", null);

      db.jdbc.update(
          "UPDATE needs SET starts_at=now()-interval '2 hours',ends_at=now()-interval '1 hour'"
              + " WHERE id=?",
          UUID.fromString(needId));
      String participation =
          id(
              owner.put(
                  pending + "/participation",
                  view("actualHours", 2, "notes", null, "attendance", "COMPLETED")));
      runnerUp.get(pending + "/participation");
      owner.put(
          "/participation/" + participation + "/verification",
          view("decision", "VERIFIED", "reason", null));
      runnerUp.post(pending + "/feedback", view("rating", 4, "comment", null));
      owner.post(pending + "/feedback", view("rating", 5, "comment", "Comentario privado"));
      assertEquals(1, runnerUp.get(pending + "/feedback").size());
      assertEquals(2, owner.get(pending + "/feedback").size());
      owner.put(
          need + "/outcome", view("outcomeStatus", "FULFILLED", "summary", "Resultado privado"));
      assertFalse(anon.get(need).path("outcome").has("summary"));

      var bytes = new ByteArrayOutputStream();
      ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
      String attachment = id(owner.upload(need + "/evidence", bytes.toByteArray(), 201));
      String personal =
          id(
              owner.upload(
                  "/participation/" + participation + "/evidence", bytes.toByteArray(), 201));
      owner.get(need + "/evidence");
      runnerUp.get("/participation/" + participation + "/evidence");
      assertEquals(
          "RESOURCE_NOT_FOUND",
          runnerUp.error("GET", "/evidence/" + attachment + "/download-url", null, 404));
      assertEquals(
          "RESOURCE_NOT_FOUND",
          outsider.error("GET", "/evidence/" + personal + "/download-url", null, 404));
      URI signed =
          URI.create(runnerUp.get("/evidence/" + personal + "/download-url").path("url").asText());
      String download =
          signed.getRawPath().substring("/api/v1".length()) + "?" + signed.getRawQuery();
      assertEquals(200, runnerUp.send("GET", download, null, null).statusCode());
      assertEquals(404, outsider.send("GET", download, null, null).statusCode());
      assertEquals(
          404,
          runnerUp
              .send(
                  "GET",
                  signed.getRawPath().substring("/api/v1".length())
                      + "?until=1&proof="
                      + "A".repeat(43),
                  null,
                  null)
              .statusCode());
      owner.upload(need + "/evidence", new byte[] {1, 2, 3}, 415);
      owner.upload(need + "/evidence", new byte[20 * 1024 * 1024 + 1], 413);
    } finally {
      pool.shutdownNow();
    }

    admin.put(
        "/admin/users/" + owner.id + "/status",
        view("status", "SUSPENDED", "reason", "Suspensión de prueba"));
    assertEquals("AUTHENTICATION_REQUIRED", owner.error("GET", "/me", null, 401));
    memberships.recoverOwner(admin.id, UUID.fromString(org), outsider.id);
    assertEquals(
        outsider.id.toString(), outsider.get(team).path("items").get(0).path("userId").asText());
    admin.put(
        "/admin/users/" + owner.id + "/status",
        view("status", "ACTIVE", "reason", "Reactivación de prueba"));
    assertEquals("AUTHENTICATION_REQUIRED", owner.error("GET", "/me", null, 401));
    admin.get("/admin/users?status=ACTIVE&q=" + suffix);
    admin.get("/admin/audit-events?resourceType=ORGANIZATION");

    identity.request(first.email, "PASSWORD_RESET");
    String resetToken =
        (String)
            db.one(
                    """
                    SELECT payload->>'encryptedToken' AS token FROM email_outbox
                    WHERE recipient_email=? AND template_key='PASSWORD_RESET'
                    """,
                    first.email)
                .get("token");
    assertEquals(
        204,
        first
            .send(
                "POST",
                "/auth/password-resets",
                json.writeValueAsBytes(
                    view(
                        "token",
                        identity.decrypt(resetToken),
                        "password",
                        "different-mvp-test-password-456")),
                "application/json")
            .statusCode());
    assertEquals("AUTHENTICATION_REQUIRED", first.error("GET", "/me", null, 401));

    Set<String> productOperations = new TreeSet<>();
    mappings
        .getHandlerMethods()
        .values()
        .forEach(
            handler -> {
              Operation operation = handler.getMethodAnnotation(Operation.class);
              if (operation != null && !operation.operationId().isBlank())
                productOperations.add(operation.operationId());
            });
    productOperations.removeAll(successfulOperations);
    assertTrue(
        productOperations.isEmpty(),
        "Faltan respuestas exitosas verificadas: " + productOperations);
  }

  private String id(JsonNode body) {
    return body.path("id").asText();
  }

  private Client account(boolean admin) throws Exception {
    Client client = new Client();
    client.email = "hila-mvp-" + UUID.randomUUID() + "@example.test";
    identity.register("Cuenta MVP", client.email, PASSWORD, true, "dev-1");
    String encrypted =
        (String)
            db.one(
                    """
                    SELECT payload->>'encryptedToken' AS token FROM email_outbox
                    WHERE recipient_email=? AND template_key='EMAIL_VERIFICATION'
                    """,
                    client.email)
                .get("token");
    identity.consume(identity.decrypt(encrypted), "EMAIL_VERIFICATION", null);
    client.id = (UUID) db.one("SELECT id FROM users WHERE email=?", client.email).get("id");
    if (admin)
      db.jdbc.update(
          "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE scope='PLATFORM'"
              + " AND code='ADMIN'",
          client.id);
    String anonymous = client.get("/auth/csrf").path("token").asText();
    client.csrf = anonymous;
    client.json("POST", "/auth/sessions", view("email", client.email, "password", PASSWORD), 200);
    client.csrf = client.get("/auth/csrf").path("token").asText();
    assertNotEquals(anonymous, client.csrf);
    return client;
  }

  private final class Client {
    private final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    private String csrf;
    private String email;
    private UUID id;

    JsonNode get(String path) throws Exception {
      return json("GET", path, null, 200);
    }

    JsonNode put(String path, Object body) throws Exception {
      return json("PUT", path, body, 200);
    }

    JsonNode patch(String path, Object body) throws Exception {
      return json("PATCH", path, body, 200);
    }

    JsonNode post(String path, Object body) throws Exception {
      return json(
          "POST",
          path,
          body,
          path.endsWith("/publication")
                  || path.endsWith("/cancellation")
                  || path.endsWith("/withdrawal")
              ? 200
              : 201);
    }

    String error(String method, String path, Object body, int status) throws Exception {
      return json(method, path, body, status).path("code").asText();
    }

    HttpResponse<String> send(String method, String path, byte[] payload, String mediaType)
        throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
      if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
      if (mediaType != null) request.header("Content-Type", mediaType);
      return http.send(
          request
              .method(
                  method,
                  payload == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofByteArray(payload))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(String method, String path, Object body, int status) throws Exception {
      return verify(
          method,
          path,
          status,
          send(
              method,
              path,
              body == null ? null : json.writeValueAsBytes(body),
              body == null ? null : "application/json"));
    }

    JsonNode upload(String path, byte[] file, int status) throws Exception {
      String boundary = new String(BOUNDARY, StandardCharsets.US_ASCII);
      var out = new ByteArrayOutputStream();
      out.write(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"prueba.png\"\r\n"
                  + "Content-Type: image/png\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.write(file);
      out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
      return verify(
          "POST",
          path,
          status,
          send("POST", path, out.toByteArray(), "multipart/form-data; boundary=" + boundary));
    }
  }

  private JsonNode verify(String method, String target, int status, HttpResponse<String> response) {
    assertEquals(status, response.statusCode(), method + " " + target + " → " + response.body());
    assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
    JsonNode body = json.readTree(response.body());
    String path = target.split("\\?", 2)[0];
    var exact = contract.path("paths").path(path);
    var info =
        exact.isMissingNode()
            ? contract.path("paths").properties().stream()
                .filter(entry -> path.matches(routePattern(entry.getKey())))
                .map(entry -> entry.getValue().path(method.toLowerCase(Locale.ROOT)))
                .filter(node -> !node.isMissingNode())
                .findFirst()
                .orElseThrow(
                    () -> new AssertionError("Operación sin contrato: " + method + " " + path))
            : exact.path(method.toLowerCase(Locale.ROOT));
    var result = resolve(info.path("responses").path(Integer.toString(status)));
    assertFalse(result.isMissingNode(), "Falta código " + status + " en contrato para " + target);
    validate(
        result.path("content").path("application/json").path("schema"),
        body,
        method + " " + target);
    if (status == 201)
      assertTrue(response.headers().firstValue("location").orElseThrow().startsWith("/api/v1/"));
    if (status < 300) successfulOperations.add(info.path("operationId").asText());
    return body;
  }

  private JsonNode resolve(JsonNode schema) {
    return schema.has("$ref") ? contract.at(schema.path("$ref").asText().substring(1)) : schema;
  }

  private void validate(JsonNode raw, JsonNode value, String path) {
    var schema = resolve(raw);
    assertFalse(schema.isMissingNode(), "Falta esquema para " + path);
    if (value.isNull()) {
      assertTrue(schema.path("nullable").asBoolean(), path + " no admite null");
      return;
    }
    if (schema.has("allOf")) schema.get("allOf").forEach(item -> validate(item, value, path));
    switch (schema.path("type").asText()) {
      case "object" -> {
        assertTrue(value.isObject(), path);
        schema
            .path("required")
            .forEach(field -> assertTrue(value.has(field.asText()), path + "." + field));
        var properties = schema.path("properties");
        value
            .properties()
            .forEach(
                field -> {
                  if (properties.has(field.getKey()))
                    validate(
                        properties.path(field.getKey()),
                        field.getValue(),
                        path + "." + field.getKey());
                  else
                    assertTrue(
                        schema.path("additionalProperties").asBoolean(true),
                        path + " incluye " + field.getKey());
                });
      }
      case "array" -> {
        assertTrue(value.isArray(), path);
        value.forEach(item -> validate(schema.path("items"), item, path + "[]"));
      }
      case "integer" -> assertTrue(value.isIntegralNumber(), path);
      case "number" -> assertTrue(value.isNumber(), path);
      case "boolean" -> assertTrue(value.isBoolean(), path);
      case "string" -> {
        assertTrue(value.isString(), path);
        switch (schema.path("format").asText()) {
          case "uuid" -> assertDoesNotThrow(() -> UUID.fromString(value.asText()), path);
          case "date-time" -> assertDoesNotThrow(() -> OffsetDateTime.parse(value.asText()), path);
          case "uri" -> assertTrue(URI.create(value.asText()).isAbsolute(), path);
          default -> {}
        }
      }
      default -> {}
    }
    if (schema.has("enum"))
      assertTrue(schema.path("enum").values().contains(value), path + " enum: " + value);
  }
}
