package co.hila.api.volunteer;

import co.hila.api.identity.IdentityService;
import co.hila.api.shared.MvpRequests.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CapacityController {
  private final CapacityService service;
  private final IdentityService identity;

  public CapacityController(CapacityService service, IdentityService identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/me/skills")
  @Operation(operationId = "getMySkills")
  public List<Map<String, Object>> getMySkills(Authentication actor) {
    return service.getMySkills(identity.active(actor.getName()));
  }

  @PutMapping("/me/skills")
  @Operation(operationId = "putMySkills")
  public List<Map<String, Object>> putMySkills(
      Authentication actor, @Valid @RequestBody SkillsUpdate body) {
    return service.putMySkills(identity.active(actor.getName()), body);
  }

  @GetMapping("/me/availability")
  @Operation(operationId = "getMyAvailability")
  public Map<String, Object> getMyAvailability(Authentication actor) {
    return service.getMyAvailability(identity.active(actor.getName()));
  }

  @PutMapping("/me/availability")
  @Operation(operationId = "putMyAvailability")
  public Map<String, Object> putMyAvailability(
      Authentication actor, @Valid @RequestBody Availability body) {
    return service.putMyAvailability(identity.active(actor.getName()), body);
  }

  @GetMapping("/me/service-areas")
  @Operation(operationId = "getMyServiceAreas")
  public List<Map<String, Object>> getMyServiceAreas(Authentication actor) {
    return service.getMyServiceAreas(identity.active(actor.getName()));
  }

  @PutMapping("/me/service-areas")
  @Operation(operationId = "putMyServiceAreas")
  public List<Map<String, Object>> putMyServiceAreas(
      Authentication actor, @Valid @RequestBody ServiceAreasUpdate body) {
    return service.putMyServiceAreas(identity.active(actor.getName()), body);
  }

  @GetMapping("/me/resources")
  @Operation(operationId = "getMyResources")
  public List<Map<String, Object>> getMyResources(Authentication actor) {
    return service.getMyResources(identity.active(actor.getName()));
  }

  @PutMapping("/me/resources")
  @Operation(operationId = "putMyResources")
  public List<Map<String, Object>> putMyResources(
      Authentication actor, @Valid @RequestBody ResourcesUpdate body) {
    return service.putMyResources(identity.active(actor.getName()), body);
  }
}
