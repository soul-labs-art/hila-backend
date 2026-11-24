package co.hila.api.admin;

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
public class CatalogController {
  private final CatalogService service;
  private final IdentityService identity;

  public CatalogController(CatalogService service, IdentityService identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/catalogs/skills")
  @Operation(operationId = "listSkillCatalog")
  public Map<String, Object> listSkillCatalog(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.listSkillCatalog(q, category, page, size);
  }

  @GetMapping("/admin/catalogs/skills")
  @Operation(operationId = "adminListSkill")
  public Map<String, Object> adminListSkill(
      Authentication actor,
      @RequestParam(required = false) Boolean active,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.adminListSkill(identity.active(actor.getName()), active, q, page, size);
  }

  @PostMapping("/admin/catalogs/skills")
  @Operation(operationId = "adminCreateSkill")
  public ResponseEntity<Map<String, Object>> adminCreateSkill(
      Authentication actor, @Valid @RequestBody SkillWrite body) {
    var result = service.adminCreateSkill(identity.active(actor.getName()), body);
    return ResponseEntity.created(
            URI.create("/api/v1" + "/admin/catalogs/skills/" + result.get("id")))
        .body(result);
  }

  @PutMapping("/admin/catalogs/skills/{entryId}")
  @Operation(operationId = "adminUpdateSkill")
  public Map<String, Object> adminUpdateSkill(
      Authentication actor, @PathVariable UUID entryId, @Valid @RequestBody SkillWrite body) {
    return service.adminUpdateSkill(identity.active(actor.getName()), entryId, body);
  }

  @GetMapping("/catalogs/resources")
  @Operation(operationId = "listResourceCatalog")
  public Map<String, Object> listResourceCatalog(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.listResourceCatalog(q, category, page, size);
  }

  @GetMapping("/admin/catalogs/resources")
  @Operation(operationId = "adminListResource")
  public Map<String, Object> adminListResource(
      Authentication actor,
      @RequestParam(required = false) Boolean active,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.adminListResource(identity.active(actor.getName()), active, q, page, size);
  }

  @PostMapping("/admin/catalogs/resources")
  @Operation(operationId = "adminCreateResource")
  public ResponseEntity<Map<String, Object>> adminCreateResource(
      Authentication actor, @Valid @RequestBody ResourceWrite body) {
    var result = service.adminCreateResource(identity.active(actor.getName()), body);
    return ResponseEntity.created(
            URI.create("/api/v1" + "/admin/catalogs/resources/" + result.get("id")))
        .body(result);
  }

  @PutMapping("/admin/catalogs/resources/{entryId}")
  @Operation(operationId = "adminUpdateResource")
  public Map<String, Object> adminUpdateResource(
      Authentication actor, @PathVariable UUID entryId, @Valid @RequestBody ResourceWrite body) {
    return service.adminUpdateResource(identity.active(actor.getName()), entryId, body);
  }

  @GetMapping("/admin/catalogs/territories")
  @Operation(operationId = "adminListTerritory")
  public Map<String, Object> adminListTerritory(
      Authentication actor,
      @RequestParam(required = false) Boolean active,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.adminListTerritory(identity.active(actor.getName()), active, q, page, size);
  }

  @PostMapping("/admin/catalogs/territories")
  @Operation(operationId = "adminCreateTerritory")
  public ResponseEntity<Map<String, Object>> adminCreateTerritory(
      Authentication actor, @Valid @RequestBody TerritoryWrite body) {
    var result = service.adminCreateTerritory(identity.active(actor.getName()), body);
    return ResponseEntity.created(
            URI.create("/api/v1" + "/admin/catalogs/territories/" + result.get("id")))
        .body(result);
  }

  @PutMapping("/admin/catalogs/territories/{entryId}")
  @Operation(operationId = "adminUpdateTerritory")
  public Map<String, Object> adminUpdateTerritory(
      Authentication actor, @PathVariable UUID entryId, @Valid @RequestBody TerritoryWrite body) {
    return service.adminUpdateTerritory(identity.active(actor.getName()), entryId, body);
  }
}
