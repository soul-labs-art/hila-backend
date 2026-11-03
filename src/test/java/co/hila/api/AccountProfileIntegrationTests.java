package co.hila.api;

import static org.junit.jupiter.api.Assertions.*;

import co.hila.api.identity.IdentityService;
import co.hila.api.media.*;
import co.hila.api.profiles.*;
import co.hila.api.shared.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(properties = {"hila.outbox-delay=3600000"})
class AccountProfileIntegrationTests {
  @Autowired IdentityService identity;
  @Autowired ProfileService profiles;
  @Autowired ImageService images;
  @Autowired Database db;

  UUID account() {
    String email = "test-" + UUID.randomUUID() + "@example.test";
    identity.register("Persona prueba", email, "correct-horse-hila-123", true, "dev-1");
    UUID id = (UUID) db.one("SELECT id FROM users WHERE email=?", email).get("id");
    String encrypted =
        (String)
            db.one(
                    "SELECT payload->>'encryptedToken' AS token FROM email_outbox WHERE"
                        + " recipient_email=?",
                    email)
                .get("token");
    String token = identity.decrypt(encrypted);
    identity.consume(token, "EMAIL_VERIFICATION", null);
    assertThrows(ApiFailure.class, () -> identity.consume(token, "EMAIL_VERIFICATION", null));
    return id;
  }

  @Test
  void privateByDefaultAndPublicWhitelist() {
    UUID id = account();
    profiles.saveProfile(
        id,
        new ProfileController.ProfileWrite("Presentación", "Docente", "Dato privado", "REMOTE"));
    assertThrows(ApiFailure.class, () -> profiles.publicVolunteer(id));
    profiles.publication(id, null, true);
    var data = profiles.publicVolunteer(id);
    assertEquals("Docente", data.get("profession"));
    assertFalse(data.containsKey("email"));
    assertFalse(data.containsKey("educationLevel"));
    profiles.publication(id, null, false);
    assertThrows(ApiFailure.class, () -> profiles.publicVolunteer(id));
  }

  @Test
  void organizationReviewAndOwnerIsolation() {
    UUID owner = account(), other = account();
    var w =
        new ProfileController.OrganizationWrite(
            "Colectivo",
            "COLLECTIVE",
            null,
            "Actividades comunitarias",
            "Trayectoria de prueba",
            null,
            null,
            null);
    UUID id = (UUID) profiles.createOrg(owner, w).get("id");
    assertThrows(ApiFailure.class, () -> profiles.member(other, id, true));
    assertThrows(ApiFailure.class, () -> profiles.publication(owner, id, true));
    db.jdbc.update(
        "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='ADMIN'", other);
    profiles.review(other, id, new ProfileController.Review("APPROVED", "Revisión de prueba"));
    profiles.publication(owner, id, true);
    var publicData = profiles.publicOrg(id);
    assertFalse(publicData.containsKey("legalIdentifier"));
    assertFalse(publicData.containsKey("verificationContext"));
    profiles.review(other, id, new ProfileController.Review("SUSPENDED", "Revisión de prueba"));
    assertThrows(ApiFailure.class, () -> profiles.publicOrg(id));
  }

  @Test
  void imagePrivacyReplacementAndValidation() throws Exception {
    UUID id = account();
    profiles.saveProfile(id, new ProfileController.ProfileWrite(null, null, null, null));
    var pixels = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
    var out = new ByteArrayOutputStream();
    ImageIO.write(pixels, "png", out);
    var upload = new MockMultipartFile("file", "image.png", "image/png", out.toByteArray());
    UUID image = (UUID) images.upload(id, null, "avatar", upload).get("id");
    assertNotNull(images.read(image, id));
    assertThrows(ApiFailure.class, () -> images.read(image, null));
    profiles.publication(id, null, true);
    assertNotNull(images.read(image, null));
    images.delete(id, null, "avatar");
    assertThrows(ApiFailure.class, () -> images.read(image, id));
    assertThrows(
        ApiFailure.class,
        () ->
            images.upload(
                id,
                null,
                "cover",
                new MockMultipartFile("file", "bad.png", "image/png", "not an image".getBytes())));
  }

  @Test
  void recoveryAndDuplicateDoNotAlterCredentials() {
    UUID id = account();
    String email = (String) identity.me(id).get("email");
    identity.register("Duplicada", email, "different-password-123", true, "dev-1");
    assertEquals(id, identity.authenticate(email, "correct-horse-hila-123"));
    identity.request(email, "PASSWORD_RESET");
    String encrypted =
        (String)
            db.one(
                    "SELECT payload->>'encryptedToken' AS token FROM email_outbox WHERE"
                        + " recipient_email=? AND template_key='PASSWORD_RESET' ORDER BY created_at"
                        + " DESC LIMIT 1",
                    email)
                .get("token");
    identity.consume(identity.decrypt(encrypted), "PASSWORD_RESET", "new-secure-password-123");
    assertThrows(ApiFailure.class, () -> identity.authenticate(email, "correct-horse-hila-123"));
    assertEquals(id, identity.authenticate(email, "new-secure-password-123"));
  }

  @Test
  void expiredTokenAndOutdatedConsentCannotActivateAccounts() {
    String email = "expired-" + UUID.randomUUID() + "@example.test";
    ApiFailure policy =
        assertThrows(
            ApiFailure.class,
            () -> identity.register("Prueba", email, "secure-test-password-123", true, "obsolete"));
    assertEquals("POLICY_VERSION_OUTDATED", policy.code);
    assertEquals(
        0,
        db.jdbc.queryForObject("SELECT count(*) FROM users WHERE email=?", Integer.class, email));
    identity.register("Prueba", email, "secure-test-password-123", true, "dev-1");
    String encrypted =
        (String)
            db.one(
                    "SELECT payload->>'encryptedToken' AS token FROM email_outbox WHERE"
                        + " recipient_email=?",
                    email)
                .get("token");
    String token = identity.decrypt(encrypted);
    db.jdbc.update(
        "UPDATE account_tokens SET expires_at=now()-interval '1 second' WHERE user_id=(SELECT id"
            + " FROM users WHERE email=?)",
        email);
    ApiFailure expired =
        assertThrows(ApiFailure.class, () -> identity.consume(token, "EMAIL_VERIFICATION", null));
    assertEquals("TOKEN_INVALID_OR_EXPIRED", expired.code);
    assertEquals(
        "PENDING_EMAIL", db.one("SELECT status FROM users WHERE email=?", email).get("status"));
  }

  @Test
  void imageSizeAndPixelLimitsRejectBeforeStorage() throws Exception {
    UUID id = account();
    profiles.saveProfile(id, new ProfileController.ProfileWrite(null, null, null, null));
    ApiFailure oversize =
        assertThrows(
            ApiFailure.class,
            () ->
                images.upload(
                    id,
                    null,
                    "avatar",
                    new MockMultipartFile("file", "large.png", "image/png", new byte[10485761])));
    assertEquals(413, oversize.status);
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", out);
    byte[] bomb = out.toByteArray();
    var buffer = java.nio.ByteBuffer.wrap(bomb);
    buffer.putInt(16, 8001);
    buffer.putInt(20, 2000);
    var crc = new java.util.zip.CRC32();
    crc.update(bomb, 12, 17);
    buffer.putInt(29, (int) crc.getValue());
    ApiFailure pixels =
        assertThrows(
            ApiFailure.class,
            () ->
                images.upload(
                    id,
                    null,
                    "cover",
                    new MockMultipartFile("file", "huge.png", "image/png", bomb)));
    assertEquals(422, pixels.status);
    assertEquals(
        0,
        db.jdbc.queryForObject(
            "SELECT count(*) FROM profile_images WHERE user_id=?", Integer.class, id));
  }

  @Test
  void storageFailurePreservesCurrentImageAndCleanupRemovesRevokedObject() throws Exception {
    UUID id = account();
    profiles.saveProfile(id, new ProfileController.ProfileWrite(null, null, null, null));
    ObjectStorage storage = org.mockito.Mockito.mock(ObjectStorage.class);
    var objects = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              objects.put(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(storage)
        .put(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(byte[].class),
            org.mockito.ArgumentMatchers.anyString());
    org.mockito.Mockito.when(storage.get(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> objects.get(invocation.getArgument(0)));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              objects.remove(invocation.getArgument(0));
              return null;
            })
        .when(storage)
        .delete(org.mockito.ArgumentMatchers.anyString());
    var manager =
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
            db.jdbc.getDataSource());
    var isolated = new ImageService(profiles, db, storage, manager);
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), "png", out);
    var upload = new MockMultipartFile("file", "image.png", "image/png", out.toByteArray());
    UUID original = (UUID) isolated.upload(id, null, "avatar", upload).get("id");
    profiles.publication(id, null, true);
    assertNotNull(isolated.read(original, null));
    org.mockito.Mockito.doThrow(new IOException("Simulated storage outage"))
        .when(storage)
        .put(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(byte[].class),
            org.mockito.ArgumentMatchers.anyString());
    ApiFailure failure =
        assertThrows(ApiFailure.class, () -> isolated.upload(id, null, "avatar", upload));
    assertEquals("STORAGE_UNAVAILABLE", failure.code);
    assertEquals(
        original,
        profiles.profile(id).get("avatar") instanceof Map<?, ?> avatar ? avatar.get("id") : null);
    assertNotNull(isolated.read(original, null));
    profiles.publication(id, null, false);
    assertThrows(ApiFailure.class, () -> isolated.read(original, null));
    isolated.delete(id, null, "avatar");
    assertThrows(ApiFailure.class, () -> isolated.read(original, id));
    isolated.cleanup();
    assertEquals(
        0,
        db.jdbc.queryForObject(
            "SELECT count(*) FROM profile_images WHERE user_id=?", Integer.class, id));
    assertTrue(objects.isEmpty());
  }
}
