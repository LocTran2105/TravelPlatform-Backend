package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.ModerationDto.ActionRequest;
import hcmute.edu.vn.backend.service.ModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * API cho kiểm duyệt viên / quản trị viên. SecurityConfig đã giới hạn
 * /api/moderation/** cho ROLE_MODERATOR và ROLE_ADMIN; service kiểm tra lại lần nữa.
 */
@RestController
@RequestMapping("/api/moderation")
@RequiredArgsConstructor
public class ModerationController {

    private final ModerationService moderationService;

    @GetMapping("/stats")
    public ResponseEntity<?> stats(Principal principal) {
        return run(() -> moderationService.getStats(principal.getName()));
    }

    @GetMapping("/queue")
    public ResponseEntity<?> queue(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Principal principal) {
        return run(() -> moderationService.getQueue(principal.getName(), type, page, size));
    }

    @GetMapping("/targets/{type}/{id}")
    public ResponseEntity<?> detail(@PathVariable String type, @PathVariable Long id, Principal principal) {
        return run(() -> moderationService.getDetail(principal.getName(), type, id));
    }

    @PostMapping("/targets/{type}/{id}/hide")
    public ResponseEntity<?> hide(@PathVariable String type, @PathVariable Long id,
                                  @RequestBody(required = false) ActionRequest body, Principal principal) {
        return run(() -> moderationService.hide(principal.getName(), type, id, reasonOf(body)));
    }

    @PostMapping("/targets/{type}/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable String type, @PathVariable Long id,
                                     @RequestBody(required = false) ActionRequest body, Principal principal) {
        return run(() -> moderationService.restore(principal.getName(), type, id, reasonOf(body)));
    }

    @PostMapping("/targets/{type}/{id}/dismiss")
    public ResponseEntity<?> dismiss(@PathVariable String type, @PathVariable Long id,
                                     @RequestBody(required = false) ActionRequest body, Principal principal) {
        return run(() -> moderationService.dismiss(principal.getName(), type, id, reasonOf(body)));
    }

    @GetMapping("/actions")
    public ResponseEntity<?> actions(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Principal principal) {
        return run(() -> moderationService.getActions(principal.getName(), page, size));
    }

    // ------------------------------------------------------------------

    private String reasonOf(ActionRequest body) {
        return body == null ? null : body.reason();
    }

    private ResponseEntity<?> run(Supplier<Object> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Lỗi kiểm duyệt: " + e.getMessage());
        }
    }
}
