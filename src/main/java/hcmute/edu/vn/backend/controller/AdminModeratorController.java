package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.ModeratorDto.GrantRequest;
import hcmute.edu.vn.backend.service.AdminModeratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Quản trị viên quản lý kiểm duyệt viên. SecurityConfig đã giới hạn /api/admin/** cho ROLE_ADMIN;
 * service kiểm tra lại lần nữa.
 */
@RestController
@RequestMapping("/api/admin/moderators")
@RequiredArgsConstructor
public class AdminModeratorController {

    private final AdminModeratorService service;

    @GetMapping
    public ResponseEntity<?> list(Principal principal) {
        return run(() -> service.listModerators(principal.getName()));
    }

    @GetMapping("/candidates")
    public ResponseEntity<?> candidates(@RequestParam(required = false) String q, Principal principal) {
        return run(() -> service.searchCandidates(principal.getName(), q));
    }

    @PostMapping
    public ResponseEntity<?> grant(@RequestBody(required = false) GrantRequest body, Principal principal) {
        return run(() -> service.grant(principal.getName(), body == null ? null : body.userId()));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<?> revoke(@PathVariable Long userId, Principal principal) {
        return run(() -> service.revoke(principal.getName(), userId));
    }

    // ------------------------------------------------------------------

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
            return ResponseEntity.internalServerError().body("Lỗi quản lý kiểm duyệt viên: " + e.getMessage());
        }
    }
}
