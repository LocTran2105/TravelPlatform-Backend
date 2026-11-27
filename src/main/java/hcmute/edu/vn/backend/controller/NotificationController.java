package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/** /api/notifications - cần đăng nhập (SecurityConfig: anyRequest().authenticated()). */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            Principal principal) {
        return run(principal, () -> notificationService.list(principal.getName(), unreadOnly, page, size));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<?> unreadCount(Principal principal) {
        return run(principal, () -> Map.of("count", notificationService.unreadCount(principal.getName())));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<?> markRead(@PathVariable("id") Long id, Principal principal) {
        return run(principal, () -> {
            notificationService.markRead(principal.getName(), id);
            return Map.of("success", true);
        });
    }

    @PatchMapping("/read-all")
    public ResponseEntity<?> markAllRead(Principal principal) {
        return run(principal, () -> Map.of("updated", notificationService.markAllRead(principal.getName())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") Long id, Principal principal) {
        return run(principal, () -> {
            notificationService.delete(principal.getName(), id);
            return Map.of("success", true);
        });
    }

    private ResponseEntity<?> run(Principal principal, Supplier<Object> action) {
        if (principal == null) {
            return ResponseEntity.status(401).body("Vui lòng đăng nhập.");
        }
        try {
            return ResponseEntity.ok(action.get());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Lỗi thông báo: " + e.getMessage());
        }
    }
}
