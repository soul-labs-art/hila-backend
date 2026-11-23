package co.hila.api.opportunities;

import co.hila.api.identity.IdentityService;
import co.hila.api.shared.MvpRequests.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OpportunityController {
  private final OpportunityService service;
  private final IdentityService identity;

  public OpportunityController(OpportunityService service, IdentityService identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/organizations/{organizationId}/causes")
  @Operation(operationId = "listOrganizationCauses")
  public Map<String, Object> listOrganizationCauses(
      Authentication actor,
      @PathVariable UUID organizationId,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.listOrganizationCauses(
        identity.active(actor.getName()), organizationId, page, size);
  }

  @PostMapping("/organizations/{organizationId}/causes")
  @Operation(operationId = "createCause")
  public ResponseEntity<Map<String, Object>> createCause(
      Authentication actor,
      @PathVariable UUID organizationId,
      @Valid @RequestBody CauseWrite body) {
    var result = service.createCause(identity.active(actor.getName()), organizationId, body);
    return ResponseEntity.created(URI.create("/api/v1" + "/causes/" + result.get("id")))
        .body(result);
  }

  @GetMapping("/causes/{causeId}")
  @Operation(operationId = "getCause")
  public Map<String, Object> getCause(Authentication actor, @PathVariable UUID causeId) {
    return service.getCause(identity.active(actor.getName()), causeId);
  }

  @PutMapping("/causes/{causeId}")
  @Operation(operationId = "putCause")
  public Map<String, Object> putCause(
      Authentication actor, @PathVariable UUID causeId, @Valid @RequestBody CauseWrite body) {
    return service.putCause(identity.active(actor.getName()), causeId, body);
  }

  @GetMapping("/causes/{causeId}/needs")
  @Operation(operationId = "listManagedNeeds")
  public Map<String, Object> listManagedNeeds(
      Authentication actor,
      @PathVariable UUID causeId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.listManagedNeeds(identity.active(actor.getName()), causeId, status, page, size);
  }

  @PostMapping("/causes/{causeId}/needs")
  @Operation(operationId = "createNeed")
  public ResponseEntity<Map<String, Object>> createNeed(
      Authentication actor, @PathVariable UUID causeId, @Valid @RequestBody NeedWrite body) {
    var result = service.createNeed(identity.active(actor.getName()), causeId, body);
    return ResponseEntity.created(
            URI.create("/api/v1" + "/needs/" + result.get("id") + "/management"))
        .body(result);
  }

  @GetMapping("/needs")
  @Operation(operationId = "listPublicNeeds")
  public Map<String, Object> listPublicNeeds(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID territoryId,
      @RequestParam(required = false) String modality,
      @RequestParam(required = false) UUID skillId,
      @RequestParam(required = false) OffsetDateTime startsFrom,
      @RequestParam(required = false) OffsetDateTime startsTo,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.listPublicNeeds(
        q, territoryId, modality, skillId, startsFrom, startsTo, page, size);
  }

  @GetMapping("/needs/{needId}")
  @Operation(operationId = "getPublicNeed")
  public Map<String, Object> getPublicNeed(@PathVariable UUID needId) {
    return service.getPublicNeed(needId);
  }

  @GetMapping("/needs/{needId}/management")
  @Operation(operationId = "getManagedNeed")
  public Map<String, Object> getManagedNeed(Authentication actor, @PathVariable UUID needId) {
    return service.getManagedNeed(identity.active(actor.getName()), needId);
  }

  @PutMapping("/needs/{needId}/management")
  @Operation(operationId = "putManagedNeed")
  public Map<String, Object> putManagedNeed(
      Authentication actor, @PathVariable UUID needId, @Valid @RequestBody NeedWrite body) {
    return service.putManagedNeed(identity.active(actor.getName()), needId, body);
  }

  @PostMapping("/needs/{needId}/publication")
  @Operation(operationId = "publishNeed")
  public Map<String, Object> publishNeed(Authentication actor, @PathVariable UUID needId) {
    return service.publishNeed(identity.active(actor.getName()), needId);
  }

  @PostMapping("/needs/{needId}/cancellation")
  @Operation(operationId = "cancelNeed")
  public Map<String, Object> cancelNeed(
      Authentication actor, @PathVariable UUID needId, @Valid @RequestBody ReasonRequest body) {
    return service.cancelNeed(identity.active(actor.getName()), needId, body);
  }

  @PutMapping("/needs/{needId}/outcome")
  @Operation(operationId = "closeNeed")
  public Map<String, Object> closeNeed(
      Authentication actor, @PathVariable UUID needId, @Valid @RequestBody CloseNeedRequest body) {
    return service.closeNeed(identity.active(actor.getName()), needId, body);
  }

  @GetMapping("/needs/{needId}/compatibility")
  @Operation(operationId = "getCompatibility")
  public Map<String, Object> getCompatibility(Authentication actor, @PathVariable UUID needId) {
    return service.getCompatibility(identity.active(actor.getName()), needId);
  }
}
