package co.hila.api.identity;

import co.hila.api.shared.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
  final Database db;
  final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  final byte[] key;
  public final String policyVersion;
  public final String policyContent;
  public final String alertsContent;
  final String dummyPasswordHash = encoder.encode(java.util.UUID.randomUUID().toString());

  public IdentityService(
      Database db,
      @Value("${hila.outbox-key}") String secret,
      @Value("${hila.policy.version:dev-1}") String policyVersion,
      @Value(
              "${hila.policy.content:Entorno local para pruebas. No introduzcas datos personales"
                  + " reales.}")
          String policyContent,
      @Value("${hila.policy.alerts-content:Avisos del entorno de pruebas.}") String alertsContent) {
    this.db = db;
    this.policyVersion = policyVersion;
    this.policyContent = policyContent;
    this.alertsContent = alertsContent;
    try {
      key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String encrypt(String text) {
    try {
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      return Base64.getEncoder().encodeToString(iv)
          + ":"
          + Base64.getEncoder().encodeToString(c.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String decrypt(String text) {
    try {
      String[] parts = text.split(":");
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
      return new String(c.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String normalize(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  @Transactional(noRollbackFor = ApiFailure.class)
  public void limit(String purpose, String target, int maximum) {
    String bucket = purpose + ":" + hash(target);
    var r =
        db.one(
            "INSERT INTO identity_rate_limits(bucket,attempts,resets_at) VALUES (?,1,now()+interval"
                + " '15 minutes') ON CONFLICT(bucket) DO UPDATE SET attempts=CASE WHEN"
                + " identity_rate_limits.resets_at<now() THEN 1 ELSE"
                + " identity_rate_limits.attempts+1 END,resets_at=CASE WHEN"
                + " identity_rate_limits.resets_at<now() THEN now()+interval '15 minutes' ELSE"
                + " identity_rate_limits.resets_at END RETURNING attempts",
            bucket);
    if (((Number) r.get("attempts")).intValue() > maximum)
      throw new ApiFailure(429, "RATE_LIMITED", "Espera unos minutos antes de reintentar.");
  }

  public void password(String p) {
    if (p == null || p.length() < 12 || p.length() > 128)
      throw ApiFailure.invalid("La contraseña debe tener entre 12 y 128 caracteres.");
  }

  public void policy(String version) {
    if (!policyVersion.equals(version))
      throw new ApiFailure(409, "POLICY_VERSION_OUTDATED", "Revisa y acepta la política vigente.");
  }

  @Transactional
  public void register(String name, String email, String password, boolean adult, String version) {
    policy(version);
    password(password);
    if (!adult || name.trim().isEmpty())
      throw ApiFailure.invalid("Confirma mayoría de edad y nombre.");
    UUID id = UUID.randomUUID();
    int n =
        db.jdbc.update(
            "INSERT INTO users(id,email,password_hash,display_name,adult_confirmed_at) VALUES"
                + " (?,?,?,?,now()) ON CONFLICT DO NOTHING",
            id,
            normalize(email),
            encoder.encode(password),
            name.trim());
    if (n == 0) return;
    db.jdbc.update(
        "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE scope='PLATFORM' AND"
            + " code='USER'",
        id);
    db.jdbc.update(
        "INSERT INTO consents(user_id,consent_type,document_version) VALUES"
            + " (?,'DATA_PROCESSING',?)",
        id,
        version);
    issue(id, normalize(email), "EMAIL_VERIFICATION");
    db.audit(id, "ACCOUNT_REGISTERED", "USER", id);
  }

  void issue(UUID id, String email, String purpose) {
    db.one("SELECT id FROM users WHERE id=? FOR UPDATE", id);
    db.jdbc.update(
        "UPDATE account_tokens SET consumed_at=now() WHERE user_id=? AND purpose=? AND consumed_at"
            + " IS NULL",
        id,
        purpose);
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.jdbc.update(
        "INSERT INTO account_tokens(user_id,purpose,token_hash,expires_at) VALUES (?,?,?,?)",
        id,
        purpose,
        hash(token),
        java.sql.Timestamp.from(
            Instant.now().plusSeconds(purpose.equals("EMAIL_VERIFICATION") ? 86400 : 1800)));
    String payload = "{\"encryptedToken\":\"" + encrypt(token) + "\"}";
    db.jdbc.update(
        "INSERT INTO email_outbox(recipient_email,template_key,payload) VALUES (?,?,?::jsonb)",
        email,
        purpose,
        payload);
  }

  @Transactional
  public void request(String email, String purpose) {
    var rows =
        db.jdbc.queryForList(
            "SELECT id,email,status FROM users WHERE lower(email)=? FOR UPDATE", normalize(email));
    if (rows.isEmpty()) return;
    var u = rows.get(0);
    if (purpose.equals("EMAIL_VERIFICATION") && !u.get("status").equals("PENDING_EMAIL")) return;
    if (purpose.equals("PASSWORD_RESET") && !u.get("status").equals("ACTIVE")) return;
    issue((UUID) u.get("id"), (String) u.get("email"), purpose);
  }

  @Transactional
  public void consume(String token, String purpose, String newPassword) {
    var candidate =
        db.jdbc.queryForList(
            "SELECT user_id FROM account_tokens WHERE token_hash=? AND purpose=?",
            hash(token),
            purpose);
    if (candidate.isEmpty())
      throw new ApiFailure(
          422, "TOKEN_INVALID_OR_EXPIRED", "El enlace ya no es válido. Solicita uno nuevo.");
    db.one("SELECT id FROM users WHERE id=? FOR UPDATE", candidate.get(0).get("user_id"));
    var rows =
        db.jdbc.queryForList(
            "SELECT * FROM account_tokens WHERE token_hash=? AND purpose=? AND consumed_at IS NULL"
                + " AND expires_at>now() FOR UPDATE",
            hash(token),
            purpose);
    if (rows.isEmpty())
      throw new ApiFailure(
          422, "TOKEN_INVALID_OR_EXPIRED", "El enlace ya no es válido. Solicita uno nuevo.");
    UUID user = (UUID) rows.get(0).get("user_id");
    db.one("SELECT id FROM users WHERE id=? FOR UPDATE", user);
    db.jdbc.update("UPDATE account_tokens SET consumed_at=now() WHERE id=?", rows.get(0).get("id"));
    if (purpose.equals("EMAIL_VERIFICATION")) {
      db.jdbc.update(
          "UPDATE users SET status='ACTIVE',email_verified_at=now(),updated_at=now() WHERE id=? AND"
              + " status='PENDING_EMAIL'",
          user);
    } else {
      password(newPassword);
      db.jdbc.update(
          "UPDATE users SET password_hash=?,updated_at=now() WHERE id=?",
          encoder.encode(newPassword),
          user);
      db.jdbc.update("DELETE FROM spring_session WHERE principal_name=?", user.toString());
      db.jdbc.update(
          "UPDATE account_tokens SET consumed_at=now() WHERE user_id=? AND"
              + " purpose='PASSWORD_RESET'",
          user);
    }
    db.audit(user, purpose, "USER", user);
  }

  public UUID authenticate(String email, String password) {
    var rows = db.jdbc.queryForList("SELECT * FROM users WHERE lower(email)=?", normalize(email));
    if (!encoder.matches(
            password,
            rows.isEmpty() ? dummyPasswordHash : (String) rows.get(0).get("password_hash"))
        || rows.isEmpty())
      throw new ApiFailure(401, "INVALID_CREDENTIALS", "Correo o contraseña incorrectos.");
    var row = rows.get(0);
    if (!row.get("status").equals("ACTIVE"))
      throw new ApiFailure(
          403,
          row.get("status").equals("SUSPENDED") ? "ACCOUNT_SUSPENDED" : "EMAIL_NOT_VERIFIED",
          "La cuenta no está habilitada. Revisa tu correo.");
    return (UUID) row.get("id");
  }

  public UUID active(String username) {
    UUID id = UUID.fromString(username);
    if (db.jdbc.queryForObject(
            "SELECT count(*) FROM users WHERE id=? AND status='ACTIVE'", Integer.class, id)
        != 1) throw new ApiFailure(401, "AUTHENTICATION_REQUIRED", "Inicia sesión de nuevo.");
    return id;
  }

  public List<String> roles(UUID id) {
    return db.jdbc.queryForList(
        "SELECT r.code FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE user_id=? AND"
            + " r.active ORDER BY r.code",
        String.class,
        id);
  }

  public Map<String, Object> me(UUID id) {
    var u = db.one("SELECT * FROM users WHERE id=?", id);
    Map<String, Object> m = new LinkedHashMap<>();
    for (String col : List.of("id", "email", "status")) m.put(col, u.get(col));
    m.put("displayName", u.get("display_name"));
    m.put("emailVerifiedAt", u.get("email_verified_at"));
    m.put("roles", roles(id));
    m.put(
        "volunteerProfileExists",
        db.jdbc.queryForObject(
                "SELECT count(*) FROM volunteer_profiles WHERE user_id=?", Integer.class, id)
            > 0);
    m.put("createdAt", u.get("created_at"));
    return m;
  }

  public Map<String, Object> rename(UUID id, String name) {
    db.jdbc.update("UPDATE users SET display_name=?,updated_at=now() WHERE id=?", name.trim(), id);
    return me(id);
  }

  public List<Map<String, Object>> consents(UUID id) {
    return db.jdbc.queryForList(
        "SELECT consent_type AS type,document_version AS version,accepted_at AS"
            + " \"acceptedAt\",revoked_at AS \"revokedAt\" FROM consents WHERE user_id=? ORDER BY"
            + " accepted_at",
        id);
  }

  public void sessionCreated(UUID id) {
    db.audit(id, "SESSION_CREATED", "USER", id);
  }

  public String policyVersion() {
    return policyVersion;
  }

  public String policyContent() {
    return policyContent;
  }

  public String alertsContent() {
    return alertsContent;
  }
}
