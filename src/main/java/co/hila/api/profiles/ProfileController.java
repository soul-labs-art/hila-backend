package co.hila.api.profiles;

import co.hila.api.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ProfileController {
  final ProfileService service;
  final IdentityService identity;

  public ProfileController(ProfileService s, IdentityService i) {
    service = s;
    identity = i;
  }

  public record ProfileWrite(
      @Size(max = 1200) @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          String biography,
      @Size(max = 140) @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          String profession,
      @Size(max = 80) @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          String educationLevel,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) String preferredModality) {}

  public record Publication(@NotNull Boolean visible) {}

  public record OrganizationWrite(
      @NotBlank @Size(max = 180) @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          String name,
      @NotBlank @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          String organizationType,
      @Size(max = 40) @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          String legalIdentifier,
      @NotBlank @Size(max = 2000) @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          String description,
      @Size(max = 2000) @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          String verificationContext,
      @Email @Size(max = 320) @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          String publicEmail,
      @Size(max = 500) @com.fasterxml.jackson.annotation.JsonProperty(required = true)
          String publicWebsite,
      @com.fasterxml.jackson.annotation.JsonProperty(required = true) UUID serviceTerritoryId) {}

  public record Review(@NotBlank String decision, @NotBlank @Size(max = 1000) String reason) {}

  public record Context(@NotBlank @Size(max = 2000) String context) {}

  public record Alerts(@NotNull Boolean enabled, @NotBlank String policyVersion) {}

  UUID user(Authentication a) {
    return identity.active(a.getName());
  }

  @GetMapping("/me/volunteer-profile")
  public Map<String, Object> profile(Authentication a) {
    return service.profile(user(a));
  }

  @PutMapping("/me/volunteer-profile")
  public Map<String, Object> save(Authentication a, @Valid @RequestBody ProfileWrite w) {
    return service.saveProfile(user(a), w);
  }

  @PutMapping("/me/volunteer-profile/publication")
  public Map<String, Object> publish(Authentication a, @Valid @RequestBody Publication w) {
    return service.publication(user(a), null, w.visible());
  }

  @GetMapping("/volunteers/{id}/public-profile")
  public Map<String, Object> publicVolunteer(@PathVariable UUID id) {
    return service.publicVolunteer(id);
  }

  @GetMapping("/organizations/{id}/public-profile")
  public Map<String, Object> publicOrganization(@PathVariable UUID id) {
    return service.publicOrg(id);
  }

  @GetMapping("/me/organizations")
  public Map<String, Object> mine(
      Authentication a,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.orgList(user(a), null, page, size, false);
  }

  @PostMapping("/organizations")
  public ResponseEntity<?> create(Authentication a, @Valid @RequestBody OrganizationWrite w) {
    var r = service.createOrg(user(a), w);
    return ResponseEntity.created(java.net.URI.create("/api/v1/organizations/" + r.get("id")))
        .body(r);
  }

  @GetMapping("/organizations/{id}")
  public Map<String, Object> org(Authentication a, @PathVariable UUID id) {
    service.member(user(a), id, false);
    return service.organization(id);
  }

  @PutMapping("/organizations/{id}")
  public Map<String, Object> updateOrg(
      Authentication a, @PathVariable UUID id, @Valid @RequestBody OrganizationWrite w) {
    return service.saveOrg(user(a), id, w);
  }

  @PutMapping("/organizations/{id}/publication")
  public Map<String, Object> publishOrg(
      Authentication a, @PathVariable UUID id, @Valid @RequestBody Publication w) {
    return service.publication(user(a), id, w.visible());
  }

  @GetMapping("/organizations/{id}/verifications")
  public Map<String, Object> history(
      Authentication a,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.history(user(a), id, page, size);
  }

  @PostMapping("/organizations/{id}/verification-requests")
  public ResponseEntity<?> resubmit(
      Authentication a, @PathVariable UUID id, @Valid @RequestBody Context w) {
    var r = service.resubmit(user(a), id, w.context());
    return ResponseEntity.created(
            java.net.URI.create("/api/v1/organizations/" + id + "/verifications"))
        .body(r);
  }

  @GetMapping("/organizations/{id}/memberships")
  public Map<String, Object> memberships(
      Authentication a,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.memberships(user(a), id, page, size);
  }

  @GetMapping("/admin/organizations")
  public Map<String, Object> adminList(
      Authentication a,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.orgList(user(a), status, page, size, true);
  }

  @PostMapping("/admin/organizations/{id}/reviews")
  public ResponseEntity<?> review(
      Authentication a, @PathVariable UUID id, @Valid @RequestBody Review w) {
    var r = service.review(user(a), id, w);
    return ResponseEntity.created(
            java.net.URI.create("/api/v1/organizations/" + id + "/verifications"))
        .body(r);
  }

  @GetMapping("/catalogs/territories")
  public Map<String, Object> territories(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) UUID parentId,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String q) {
    return service.territories(page, size, parentId, type, q);
  }

  @PutMapping("/me/opportunity-alerts")
  public Map<String, Object> alerts(Authentication a, @Valid @RequestBody Alerts w) {
    return service.alerts(user(a), w);
  }
}
