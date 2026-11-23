package co.hila.api.profiles;

import co.hila.api.identity.IdentityService;
import co.hila.api.shared.MvpRequests.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MembershipController {
  private final MembershipService service;
  private final IdentityService identity;

  public MembershipController(MembershipService service, IdentityService identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping("/organizations/{organizationId}/memberships")
  @Operation(operationId = "createMembership")
  public ResponseEntity<Map<String, Object>> createMembership(
      Authentication actor,
      @PathVariable UUID organizationId,
      @Valid @RequestBody MembershipCreate body) {
    var result = service.createMembership(identity.active(actor.getName()), organizationId, body);
    return ResponseEntity.created(
            URI.create(
                "/api/v1"
                    + "/organizations/"
                    + organizationId
                    + "/memberships/"
                    + result.get("userId")))
        .body(result);
  }

  @PatchMapping("/organizations/{organizationId}/memberships/{userId}")
  @Operation(operationId = "updateMembership")
  public Map<String, Object> updateMembership(
      Authentication actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID userId,
      @Valid @RequestBody MembershipUpdate body) {
    return service.updateMembership(identity.active(actor.getName()), organizationId, userId, body);
  }
}
