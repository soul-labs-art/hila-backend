package co.hila.api.evidence;

import co.hila.api.identity.IdentityService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class EvidenceController {
  private final EvidenceService service;
  private final IdentityService identity;

  public EvidenceController(EvidenceService service, IdentityService identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/needs/{needId}/evidence")
  @Operation(operationId = "listNeedEvidence")
  public Map<String, Object> listNeedEvidence(
      Authentication actor,
      @PathVariable UUID needId,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.listNeedEvidence(identity.active(actor.getName()), needId, page, size);
  }

  @PostMapping(value = "/needs/{needId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(operationId = "uploadNeedEvidence")
  public ResponseEntity<Map<String, Object>> uploadNeedEvidence(
      Authentication actor, @PathVariable UUID needId, @RequestPart MultipartFile file) {
    var result = service.uploadNeedEvidence(identity.active(actor.getName()), needId, file);
    return ResponseEntity.created(
            URI.create("/api/v1" + "/evidence/" + result.get("id") + "/download-url"))
        .body(result);
  }

  @GetMapping("/participation/{participationId}/evidence")
  @Operation(operationId = "listParticipationEvidence")
  public Map<String, Object> listParticipationEvidence(
      Authentication actor,
      @PathVariable UUID participationId,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.listParticipationEvidence(
        identity.active(actor.getName()), participationId, page, size);
  }

  @PostMapping(
      value = "/participation/{participationId}/evidence",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(operationId = "uploadParticipationEvidence")
  public ResponseEntity<Map<String, Object>> uploadParticipationEvidence(
      Authentication actor, @PathVariable UUID participationId, @RequestPart MultipartFile file) {
    var result =
        service.uploadParticipationEvidence(
            identity.active(actor.getName()), participationId, file);
    return ResponseEntity.created(
            URI.create("/api/v1" + "/evidence/" + result.get("id") + "/download-url"))
        .body(result);
  }

  @GetMapping("/evidence/{evidenceId}/download-url")
  @Operation(operationId = "getEvidenceDownload")
  public Map<String, Object> getEvidenceDownload(
      Authentication actor, @PathVariable UUID evidenceId) {
    return service.getEvidenceDownload(identity.active(actor.getName()), evidenceId);
  }

  @Hidden
  @GetMapping("/evidence/{evidenceId}/content")
  public ResponseEntity<InputStreamResource> content(
      Authentication actor,
      @PathVariable UUID evidenceId,
      @RequestParam long until,
      @RequestParam String proof) {
    var download = service.download(identity.active(actor.getName()), evidenceId, until, proof);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(download.mediaType()))
        .contentLength(download.size())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(download.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(new InputStreamResource(download.stream()));
  }
}
