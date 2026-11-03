package co.hila.api.media;

import co.hila.api.identity.IdentityService;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ImageController {
  final ImageService images;
  final IdentityService identity;

  public ImageController(ImageService s, IdentityService i) {
    images = s;
    identity = i;
  }

  UUID actor(Authentication a) {
    return identity.active(a.getName());
  }

  @PutMapping(
      value = "/me/volunteer-profile/images/{slot}",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> upload(
      Authentication a, @PathVariable String slot, @RequestPart MultipartFile file)
      throws Exception {
    return images.upload(actor(a), null, slot, file);
  }

  @DeleteMapping("/me/volunteer-profile/images/{slot}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(Authentication a, @PathVariable String slot) {
    images.delete(actor(a), null, slot);
  }

  @PutMapping(
      value = "/organizations/{org}/images/{slot}",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String, Object> orgUpload(
      Authentication a,
      @PathVariable UUID org,
      @PathVariable String slot,
      @RequestPart MultipartFile file)
      throws Exception {
    return images.upload(actor(a), org, slot, file);
  }

  @DeleteMapping("/organizations/{org}/images/{slot}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void orgRemove(Authentication a, @PathVariable UUID org, @PathVariable String slot) {
    images.delete(actor(a), org, slot);
  }

  @GetMapping("/profile-images/{id}")
  public ResponseEntity<byte[]> read(Authentication a, @PathVariable UUID id) throws Exception {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.IMAGE_PNG)
        .body(images.read(id, a == null ? null : actor(a)));
  }
}
