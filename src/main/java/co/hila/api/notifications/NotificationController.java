package co.hila.api.notifications;

import co.hila.api.identity.IdentityService;
import co.hila.api.shared.MvpRequests.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
  private final NotificationService service;
  private final IdentityService identity;

  public NotificationController(NotificationService service, IdentityService identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/me/notifications")
  @Operation(operationId = "listMyNotifications")
  public Map<String, Object> listMyNotifications(
      Authentication actor,
      @RequestParam(required = false) Boolean unreadOnly,
      @RequestParam(defaultValue = "0") Integer page,
      @RequestParam(defaultValue = "20") Integer size) {
    return service.listMyNotifications(identity.active(actor.getName()), unreadOnly, page, size);
  }

  @GetMapping("/me/notifications/unread-count")
  @Operation(operationId = "getUnreadNotificationCount")
  public Map<String, Object> getUnreadNotificationCount(Authentication actor) {
    return service.getUnreadNotificationCount(identity.active(actor.getName()));
  }

  @PatchMapping("/me/notifications/{notificationId}")
  @Operation(operationId = "readNotification")
  public Map<String, Object> readNotification(
      Authentication actor,
      @PathVariable UUID notificationId,
      @Valid @RequestBody NotificationRead body) {
    return service.readNotification(identity.active(actor.getName()), notificationId, body);
  }
}
