package co.hila.api.opportunities;

import co.hila.api.identity.IdentityService;
import co.hila.api.shared.MvpRequests.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ParticipationController {
  private final ParticipationService service;
  private final IdentityService identity;

  public ParticipationController(ParticipationService service, IdentityService identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/applications/{applicationId}/participation")
  @Operation(operationId = "getParticipation")
  public Map<String, Object> getParticipation(
      Authentication actor, @PathVariable UUID applicationId) {
    return service.getParticipation(identity.active(actor.getName()), applicationId);
  }

  @PutMapping("/applications/{applicationId}/participation")
  @Operation(operationId = "putParticipation")
  public Map<String, Object> putParticipation(
      Authentication actor,
      @PathVariable UUID applicationId,
      @Valid @RequestBody ParticipationWrite body) {
    return service.putParticipation(identity.active(actor.getName()), applicationId, body);
  }

  @PutMapping("/participation/{participationId}/verification")
  @Operation(operationId = "verifyParticipation")
  public Map<String, Object> verifyParticipation(
      Authentication actor,
      @PathVariable UUID participationId,
      @Valid @RequestBody ParticipationVerification body) {
    return service.verifyParticipation(identity.active(actor.getName()), participationId, body);
  }

  @PostMapping("/applications/{applicationId}/feedback")
  @Operation(operationId = "createFeedback")
  public ResponseEntity<Map<String, Object>> createFeedback(
      Authentication actor,
      @PathVariable UUID applicationId,
      @Valid @RequestBody FeedbackCreate body) {
    var result = service.createFeedback(identity.active(actor.getName()), applicationId, body);
    return ResponseEntity.created(
            URI.create(
                "/api/v1" + "/applications/" + applicationId + "/feedback/" + result.get("id")))
        .body(result);
  }

  @GetMapping("/applications/{applicationId}/feedback")
  @Operation(operationId = "listFeedback")
  public List<Map<String, Object>> listFeedback(
      Authentication actor, @PathVariable UUID applicationId) {
    return service.listFeedback(identity.active(actor.getName()), applicationId);
  }
}
