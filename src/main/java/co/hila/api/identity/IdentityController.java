package co.hila.api.identity;

import co.hila.api.shared.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
  final IdentityService service;

  public IdentityController(IdentityService service) {
    this.service = service;
  }

  public record Registration(
      @NotBlank @Size(max = 140) String displayName,
      @NotBlank @Email @Size(max = 320) String email,
      @NotNull @Size(min = 12, max = 128) String password,
      @NotNull @AssertTrue Boolean adultConfirmed,
      @NotBlank String dataProcessingPolicyVersion) {}

  public record EmailRequest(@NotBlank @Email @Size(max = 320) String email) {}

  public record TokenRequest(@NotBlank @Size(max = 512) String token) {}

  public record Reset(
      @NotBlank @Size(max = 512) String token,
      @NotNull @Size(min = 12, max = 128) String password) {}

  public record Login(
      @NotBlank @Email @Size(max = 320) String email, @NotNull @Size(max = 128) String password) {}

  public record Name(@NotBlank @Size(max = 140) String displayName) {}

  @GetMapping("/auth/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  @GetMapping("/policies/current")
  public List<Map<String, String>> policies() {
    return List.of(
        Map.of(
            "type",
            "DATA_PROCESSING",
            "version",
            service.policyVersion(),
            "title",
            service.policyVersion().startsWith("dev")
                ? "Política de desarrollo — no usar datos reales"
                : "Política de tratamiento de datos",
            "content",
            service.policyContent(),
            "publishedAt",
            "2026-11-03T00:00:00Z"),
        Map.of(
            "type",
            "OPPORTUNITY_ALERTS",
            "version",
            service.policyVersion(),
            "title",
            service.policyVersion().startsWith("dev")
                ? "Avisos de desarrollo"
                : "Avisos de oportunidades",
            "content",
            service.alertsContent(),
            "publishedAt",
            "2026-11-03T00:00:00Z"));
  }

  void limit(HttpServletRequest r, String target, String purpose, int max) {
    service.limit(purpose + "-ip", r.getRemoteAddr(), max);
    service.limit(purpose + "-target", target, max);
  }

  @PostMapping("/auth/registrations")
  public ResponseEntity<?> register(@Valid @RequestBody Registration r, HttpServletRequest req) {
    limit(req, service.normalize(r.email()), "register", 5);
    service.register(
        r.displayName(),
        r.email(),
        r.password(),
        r.adultConfirmed(),
        r.dataProcessingPolicyVersion());
    return ResponseEntity.accepted()
        .body(
            Map.of(
                "status",
                "VERIFICATION_REQUIRED",
                "message",
                "Revisa tu correo para continuar, si corresponde."));
  }

  @PostMapping("/auth/email-verifications")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void verify(@Valid @RequestBody TokenRequest r, HttpServletRequest req) {
    limit(req, service.hash(r.token()), "token", 10);
    service.consume(r.token(), "EMAIL_VERIFICATION", null);
  }

  @PostMapping("/auth/email-verification-requests")
  public ResponseEntity<?> resend(@Valid @RequestBody EmailRequest r, HttpServletRequest req) {
    limit(req, service.normalize(r.email()), "email", 5);
    service.request(r.email(), "EMAIL_VERIFICATION");
    return ResponseEntity.accepted()
        .body(Map.of("message", "Si corresponde, recibirás un correo."));
  }

  @PostMapping("/auth/password-reset-requests")
  public ResponseEntity<?> requestReset(
      @Valid @RequestBody EmailRequest r, HttpServletRequest req) {
    limit(req, service.normalize(r.email()), "email", 5);
    service.request(r.email(), "PASSWORD_RESET");
    return ResponseEntity.accepted()
        .body(Map.of("message", "Si corresponde, recibirás un correo."));
  }

  @PostMapping("/auth/password-resets")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(@Valid @RequestBody Reset r, HttpServletRequest req) {
    limit(req, service.hash(r.token()), "token", 10);
    service.consume(r.token(), "PASSWORD_RESET", r.password());
  }

  @PostMapping("/auth/sessions")
  public Map<String, Object> login(
      @Valid @RequestBody Login r, HttpServletRequest req, HttpServletResponse res) {
    limit(req, service.normalize(r.email()), "login", 10);
    UUID id = service.authenticate(r.email(), r.password());
    req.getSession();
    req.changeSessionId();
    req.getSession()
        .removeAttribute(
            "org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN");
    var auth =
        new UsernamePasswordAuthenticationToken(
            id.toString(),
            null,
            service.roles(id).stream()
                .map(
                    role ->
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                            "ROLE_" + role))
                .toList());
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(auth);
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository().saveContext(context, req, res);
    service.sessionCreated(id);
    return service.me(id);
  }

  @DeleteMapping("/auth/sessions/current")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest req, HttpServletResponse res) {
    if (req.getSession(false) != null) req.getSession(false).invalidate();
    SecurityContextHolder.clearContext();
    var c = new Cookie("JSESSIONID", "");
    c.setPath("/");
    c.setMaxAge(0);
    c.setHttpOnly(true);
    c.setSecure(req.isSecure());
    c.setAttribute("SameSite", "Lax");
    res.addCookie(c);
  }

  @GetMapping("/me")
  public Map<String, Object> me(Authentication a) {
    return service.me(service.active(a.getName()));
  }

  @PatchMapping("/me")
  public Map<String, Object> update(Authentication a, @Valid @RequestBody Name r) {
    UUID id = service.active(a.getName());
    return service.rename(id, r.displayName());
  }

  @GetMapping("/me/consents")
  public List<Map<String, Object>> consents(Authentication a) {
    return service.consents(service.active(a.getName()));
  }
}
