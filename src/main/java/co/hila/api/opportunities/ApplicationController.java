package co.hila.api.opportunities;

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
public class ApplicationController {
  private final ApplicationService service;
  private final IdentityService identity;

  public ApplicationController(ApplicationService service, IdentityService identity) {
    this.service = service;
    this.identity = identity;
  }

  @PostMapping("/needs/{needId}/applications")
  @Operation(operationId = "createApplication")
  public ResponseEntity<Map<String, Object>> createApplication(
      Authentication actor, @PathVariable UUID needId, @Valid @RequestBody ApplicationCreate body) {
    var result = service.createApplication(identity.active(actor.getName()), needId, body);
    return ResponseEntity.created(URI.create("/api/v1" + "/applications/" + result.get("id")))
        .body(result);
  }

  @GetMapping("/needs/{needId}/applications")
  @Operation(operationId = "listNeedApplications")
  public Map<String, Object> listNeedApplications(
      Authentication actor,
      @PathVariable UUID needId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String commitmentStatus,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.listNeedApplications(
        identity.active(actor.getName()), needId, status, commitmentStatus, page, size);
  }

  @GetMapping("/me/applications")
  @Operation(operationId = "listMyApplications")
  public Map<String, Object> listMyApplications(
      Authentication actor,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String commitmentStatus,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.listMyApplications(
        identity.active(actor.getName()), status, commitmentStatus, page, size);
  }

  @GetMapping("/applications/{applicationId}")
  @Operation(operationId = "getApplication")
  public Map<String, Object> getApplication(
      Authentication actor, @PathVariable UUID applicationId) {
    return service.getApplication(identity.active(actor.getName()), applicationId);
  }

  @PutMapping("/applications/{applicationId}/decision")
  @Operation(operationId = "decideApplication")
  public Map<String, Object> decideApplication(
      Authentication actor,
      @PathVariable UUID applicationId,
      @Valid @RequestBody ApplicationDecision body) {
    return service.decideApplication(identity.active(actor.getName()), applicationId, body);
  }

  @PostMapping("/applications/{applicationId}/withdrawal")
  @Operation(operationId = "withdrawApplication")
  public Map<String, Object> withdrawApplication(
      Authentication actor, @PathVariable UUID applicationId) {
    return service.withdrawApplication(identity.active(actor.getName()), applicationId);
  }

  @PutMapping("/applications/{applicationId}/commitment")
  @Operation(operationId = "confirmCommitment")
  public Map<String, Object> confirmCommitment(
      Authentication actor, @PathVariable UUID applicationId) {
    return service.confirmCommitment(identity.active(actor.getName()), applicationId);
  }

  @PostMapping("/applications/{applicationId}/commitment/cancellation")
  @Operation(operationId = "cancelCommitment")
  public Map<String, Object> cancelCommitment(
      Authentication actor,
      @PathVariable UUID applicationId,
      @Valid @RequestBody CommitmentCancellationRequest body) {
    return service.cancelCommitment(identity.active(actor.getName()), applicationId, body);
  }

  @GetMapping("/applications/{applicationId}/activity-instructions")
  @Operation(operationId = "getActivityInstructions")
  public Map<String, Object> getActivityInstructions(
      Authentication actor, @PathVariable UUID applicationId) {
    return service.getActivityInstructions(identity.active(actor.getName()), applicationId);
  }
}
