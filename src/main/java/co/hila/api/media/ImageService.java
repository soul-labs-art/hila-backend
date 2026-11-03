package co.hila.api.media;

import co.hila.api.profiles.ProfileService;
import co.hila.api.shared.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImageService {
  final ProfileService profiles;
  final Database db;
  final ObjectStorage storage;
  final TransactionTemplate tx;

  public ImageService(
      ProfileService profiles,
      Database db,
      ObjectStorage storage,
      org.springframework.transaction.PlatformTransactionManager manager) {
    this.profiles = profiles;
    this.db = db;
    this.storage = storage;
    tx = new TransactionTemplate(manager);
  }

  void authorize(UUID user, UUID org) {
    if (org == null) profiles.profile(user);
    else profiles.member(user, org, true);
  }

  void slot(String slot) {
    if (!List.of("avatar", "cover").contains(slot)) throw ApiFailure.missing();
  }

  public Map<String, Object> upload(UUID user, UUID org, String slot, MultipartFile file)
      throws Exception {
    slot(slot);
    authorize(user, org);
    if (file.getSize() > 10485760)
      throw new ApiFailure(413, "FILE_TOO_LARGE", "La imagen supera 10 MiB.");
    byte[] input = file.getBytes();
    int width, height;
    try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext())
        throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE", "Usa una imagen JPEG o PNG.");
      var reader = readers.next();
      try {
        reader.setInput(stream);
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!List.of("jpeg", "jpg", "png").contains(format))
          throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE", "Usa una imagen JPEG o PNG.");
        width = reader.getWidth(0);
        height = reader.getHeight(0);
        if ((long) width * height > 16000000)
          throw ApiFailure.invalid("La imagen supera 16 megapíxeles.");
      } finally {
        reader.dispose();
      }
    } catch (ApiFailure e) {
      throw e;
    } catch (Exception e) {
      throw new ApiFailure(415, "UNSUPPORTED_MEDIA_TYPE", "La imagen no es válida.");
    }
    int maxW = slot.equals("avatar") ? 512 : 1800, maxH = slot.equals("avatar") ? 512 : 600;
    double scale = Math.min(1, Math.min((double) width / maxW, (double) height / maxH));
    int targetW = Math.max(1, (int) (maxW * scale)), targetH = Math.max(1, (int) (maxH * scale));
    BufferedImage image =
        Thumbnails.of(new ByteArrayInputStream(input))
            .useExifOrientation(true)
            .crop(Positions.CENTER)
            .size(targetW, targetH)
            .asBufferedImage();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    UUID id = UUID.randomUUID();
    String key = id + ".png";
    db.jdbc.update(
        "INSERT INTO"
            + " profile_images(id,user_id,organization_id,slot,object_key,content_type,width,height,state)"
            + " VALUES (?,?,?,?,?,'image/png',?,?,'PENDING')",
        id,
        org == null ? user : null,
        org,
        slot,
        key,
        image.getWidth(),
        image.getHeight());
    try {
      storage.put(key, out.toByteArray(), "image/png");
      tx.executeWithoutResult(
          status -> {
            if (org == null)
              db.one("SELECT user_id FROM volunteer_profiles WHERE user_id=? FOR UPDATE", user);
            else {
              db.one("SELECT id FROM organizations WHERE id=? FOR UPDATE", org);
              profiles.member(user, org, true);
            }
            db.jdbc.update(
                "UPDATE profile_images SET state='DELETE_PENDING' WHERE state='ACTIVE' AND slot=?"
                    + " AND "
                    + (org == null ? "user_id=?" : "organization_id=?"),
                slot,
                org == null ? user : org);
            db.jdbc.update("UPDATE profile_images SET state='ACTIVE' WHERE id=?", id);
            db.audit(
                user,
                "PROFILE_IMAGE_UPDATED",
                org == null ? "USER" : "ORGANIZATION",
                org == null ? user : org);
          });
    } catch (Exception e) {
      db.jdbc.update("UPDATE profile_images SET state='DELETE_PENDING' WHERE id=?", id);
      throw new ApiFailure(
          503, "STORAGE_UNAVAILABLE", "No se pudo guardar la imagen. La anterior se conserva.");
    }
    return profiles.image(user, org, slot);
  }

  public void delete(UUID user, UUID org, String slot) {
    slot(slot);
    authorize(user, org);
    tx.executeWithoutResult(
        status -> {
          if (org == null)
            db.one("SELECT user_id FROM volunteer_profiles WHERE user_id=? FOR UPDATE", user);
          else {
            db.one("SELECT id FROM organizations WHERE id=? FOR UPDATE", org);
            profiles.member(user, org, true);
          }
          db.jdbc.update(
              "UPDATE profile_images SET state='DELETE_PENDING' WHERE state='ACTIVE' AND slot=? AND"
                  + " "
                  + (org == null ? "user_id=?" : "organization_id=?"),
              slot,
              org == null ? user : org);
        });
  }

  public byte[] read(UUID image, UUID actor) throws Exception {
    var row = db.one("SELECT * FROM profile_images WHERE id=? AND state='ACTIVE'", image);
    UUID user = (UUID) row.get("user_id"), org = (UUID) row.get("organization_id");
    boolean permitted = false;
    if (actor != null) {
      if (user != null && user.equals(actor)) permitted = true;
      else if (org != null) {
        try {
          profiles.member(actor, org, false);
          permitted = true;
        } catch (ApiFailure ignored) {
        }
      }
    }
    if (!permitted) {
      if (user != null) profiles.publicVolunteer(user);
      else profiles.publicOrg(org);
    }
    return storage.get((String) row.get("object_key"));
  }

  @Scheduled(fixedDelay = 60000)
  public void cleanup() {
    db.jdbc.update(
        "UPDATE profile_images SET state='DELETE_PENDING' WHERE state='PENDING' AND"
            + " created_at<now()-interval '1 hour'");
    for (var row :
        db.jdbc.queryForList(
            "SELECT id,object_key FROM profile_images WHERE state='DELETE_PENDING'")) {
      try {
        storage.delete((String) row.get("object_key"));
        db.jdbc.update(
            "DELETE FROM profile_images WHERE id=? AND state='DELETE_PENDING'", row.get("id"));
      } catch (Exception ignored) {
        /* Retry next minute; old images cannot be read through the API. */
      }
    }
  }
}
