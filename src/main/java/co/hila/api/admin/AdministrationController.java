package co.hila.api.admin;

import co.hila.api.identity.IdentityService;
import co.hila.api.shared.MvpRequests.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AdministrationController {
  private final AdministrationService service;
  private final IdentityService identity;

  public AdministrationController(AdministrationService service, IdentityService identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/admin/users")
  @Operation(operationId = "adminListUsers")
  public Map<String, Object> adminListUsers(
      Authentication actor,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.adminListUsers(identity.active(actor.getName()), q, status, page, size);
  }

  @PutMapping("/admin/users/{userId}/status")
  @Operation(operationId = "adminUpdateUserStatus")
  public Map<String, Object> adminUpdateUserStatus(
      Authentication actor,
      @PathVariable UUID userId,
      @Valid @RequestBody AccountStatusUpdate body) {
    return service.adminUpdateUserStatus(identity.active(actor.getName()), userId, body);
  }

  @GetMapping("/admin/audit-events")
  @Operation(operationId = "adminListAuditEvents")
  public Map<String, Object> adminListAuditEvents(
      Authentication actor,
      @RequestParam(required = false) UUID actorUserId,
      @RequestParam(required = false) UUID resourceId,
      @RequestParam(required = false) String resourceType,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.adminListAuditEvents(
        identity.active(actor.getName()),
        actorUserId,
        resourceId,
        resourceType,
        action,
        from,
        to,
        page,
        size);
  }
}
