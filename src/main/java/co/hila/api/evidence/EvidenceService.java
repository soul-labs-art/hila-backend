package co.hila.api.evidence;

import static co.hila.api.shared.MvpStore.*;

import co.hila.api.media.ObjectStorage;
import co.hila.api.opportunities.*;
import co.hila.api.shared.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EvidenceService {
  private static final int MAX_BYTES = 20 * 1024 * 1024;
  private static final int MAX_PIXELS = 32_000_000;
  private final Database db;
  private final MvpStore store;
  private final OpportunityService opportunities;
  private final ParticipationService participation;
  private final ApplicationService applications;
  private final ObjectStorage storage;
  private final String origin;
  private final byte[] key;

  public EvidenceService(
      MvpStore store,
      OpportunityService opportunities,
      ParticipationService participation,
      ApplicationService applications,
      ObjectStorage storage,
      @Value("${hila.web-origin}") String origin,
      @Value("${hila.outbox-key}") String secret) {
    this.db = store.db;
    this.store = store;
    this.opportunities = opportunities;
    this.participation = participation;
    this.applications = applications;
    this.storage = storage;
    this.origin = origin.replaceAll("/+$", "");
    this.key = hmac(secret.getBytes(StandardCharsets.UTF_8), "hila:evidence:downloads:v1");
  }

  public Map<String, Object> listNeedEvidence(UUID actor, UUID id, int page, int size) {
    opportunities.requireReader(actor, opportunities.organizationForNeed(id));
    return list("need_id", id, page, size);
  }

  public Map<String, Object> listParticipationEvidence(UUID actor, UUID id, int page, int size) {
    applications.access(actor, participation.applicationForParticipation(id), false).reader();
    return list("participation_record_id", id, page, size);
  }

  private Map<String, Object> list(String column, UUID id, int page, int size) {
    return store.page(
        new Filter("SELECT * FROM evidence_files").add(column + "=?", id),
        "uploaded_at DESC,id DESC",
        page,
        size,
        this::evidenceView);
  }

  @Transactional
  public Map<String, Object> uploadNeedEvidence(UUID actor, UUID id, MultipartFile file) {
    UUID organization = opportunities.organizationForNeed(id);
    opportunities.requireCoordinator(actor, organization);
    if (!"CLOSED".equals(db.one("SELECT status FROM needs WHERE id=?", id).get("status")))
      throw transition("La evidencia se adjunta a una necesidad cerrada.");
    return upload(actor, id, null, file);
  }

  @Transactional
  public Map<String, Object> uploadParticipationEvidence(UUID actor, UUID id, MultipartFile file) {
    applications.access(actor, participation.applicationForParticipation(id), true).coordination();
    return upload(actor, null, id, file);
  }

  private Map<String, Object> upload(
      UUID actor, UUID need, UUID participationId, MultipartFile file) {
    if (file.isEmpty()) throw ApiFailure.invalid("Selecciona un archivo de evidencia.");
    if (file.getSize() > MAX_BYTES)
      throw new ApiFailure(413, "FILE_TOO_LARGE", "La evidencia supera 20 MiB.");
    byte[] data;
    try {
      data = file.getBytes();
    } catch (IOException e) {
      throw ApiFailure.invalid("No pudimos leer el archivo.");
    }
    String mediaType = detectedType(data);
    UUID id = UUID.randomUUID();
    String objectKey = "evidence-" + UUID.randomUUID();
    String name = safeFilename(file.getOriginalFilename());
    try {
      storage.put(objectKey, data, mediaType);
    } catch (Exception e) {
      deleteObject(objectKey);
      throw storageUnavailable();
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) deleteObject(objectKey);
          }
        });
    var row =
        db.one(
            """
            INSERT INTO evidence_files(id,need_id,participation_record_id,object_key,original_filename,media_type,size_bytes,uploaded_by)
            VALUES(?,?,?,?,?,?,?,?) RETURNING *
            """,
            id,
            need,
            participationId,
            objectKey,
            name,
            mediaType,
            (long) data.length,
            actor);
    db.audit(actor, "EVIDENCE_UPLOADED", "EVIDENCE", id);
    return evidenceView(row);
  }

  private String safeFilename(String filename) {
    String name =
        Objects.toString(filename, "evidencia").replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
    return name.isEmpty() ? "evidencia" : name.substring(0, Math.min(name.length(), 255));
  }

  private Map<String, Object> evidenceView(Map<String, Object> row) {
    var result =
        fields(
            row,
            "id",
            "needId",
            "originalFilename",
            "mediaType",
            "sizeBytes",
            "uploadedBy",
            "uploadedAt");
    result.put("participationId", row.get("participation_record_id"));
    return result;
  }

  private String detectedType(byte[] data) {
    if (data.length >= 5 && new String(data, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
      try (var pdf = Loader.loadPDF(data)) {
        if (pdf.getNumberOfPages() > 0 && !pdf.isEncrypted()) return "application/pdf";
      } catch (IOException | RuntimeException ignored) {
        // A PDF header alone is insufficient.
      }
      throw unsupported();
    }
    try (var image = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
      var readers = ImageIO.getImageReaders(image);
      if (!readers.hasNext()) throw unsupported();
      var reader = readers.next();
      try {
        reader.setInput(image);
        String type = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!List.of("jpeg", "png").contains(type)) throw unsupported();
        if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS)
          throw ApiFailure.invalid("La imagen de evidencia excede 32 megapíxeles.");
        reader.read(0);
        return "image/" + type;
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw unsupported();
    }
  }

  private ApiFailure unsupported() {
    return new ApiFailure(
        415, "UNSUPPORTED_MEDIA_TYPE", "La evidencia debe ser un archivo JPEG, PNG o PDF válido.");
  }

  private ApiFailure storageUnavailable() {
    return new ApiFailure(503, "STORAGE_UNAVAILABLE", "El almacenamiento no está disponible.");
  }

  private void deleteObject(String objectKey) {
    try {
      storage.delete(objectKey);
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(EvidenceService.class)
          .warn("Could not clean up a rolled-back evidence upload");
    }
  }

  private Map<String, Object> authorize(UUID actor, UUID id) {
    var row = db.one("SELECT * FROM evidence_files WHERE id=?", id);
    UUID evidenceNeed = (UUID) row.get("need_id");
    if (evidenceNeed != null) {
      opportunities.requireReader(actor, opportunities.organizationForNeed(evidenceNeed));
    } else {
      UUID application =
          participation.applicationForParticipation((UUID) row.get("participation_record_id"));
      applications.access(actor, application, false).reader();
    }
    return row;
  }

  public Map<String, Object> getEvidenceDownload(UUID actor, UUID id) {
    authorize(actor, id);
    long expires = Instant.now().plusSeconds(60).getEpochSecond();
    return view(
        "url",
        origin
            + "/api/v1/evidence/"
            + id
            + "/content?until="
            + expires
            + "&proof="
            + signature(actor, id, expires),
        "expiresAt",
        Instant.ofEpochSecond(expires).toString());
  }

  public record Download(InputStream stream, String filename, String mediaType, long size) {}

  public Download download(UUID actor, UUID id, long expires, String proof) {
    long now = Instant.now().getEpochSecond();
    if (expires <= now
        || expires > now + 60
        || proof.length() != 43
        || !MessageDigest.isEqual(
            signature(actor, id, expires).getBytes(StandardCharsets.US_ASCII),
            proof.getBytes(StandardCharsets.US_ASCII))) throw ApiFailure.missing();
    var row = authorize(actor, id);
    try {
      return new Download(
          storage.open((String) row.get("object_key")),
          (String) row.get("original_filename"),
          (String) row.get("media_type"),
          ((Number) row.get("size_bytes")).longValue());
    } catch (Exception e) {
      throw storageUnavailable();
    }
  }

  private String signature(UUID actor, UUID id, long expires) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(hmac(key, actor + ":" + id + ":" + expires));
  }

  private static byte[] hmac(byte[] key, String data) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
