package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.ShareDtos.JoinRequest;
import hcmute.edu.vn.backend.dto.ShareDtos.RoleRequest;
import hcmute.edu.vn.backend.service.InvitationException;
import hcmute.edu.vn.backend.service.TripShareService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.function.Supplier;

/**
 * Nút Share.
 *   Công khai    : GET  /api/share/preview/{token}
 *   Cần đăng nhập: các API còn lại
 * Lỗi trả về là chuỗi thông báo (khớp cách frontend đang đọc error.response.data).
 */
@RestController
@RequestMapping("/api")
public class ShareController {

    private static final Logger log = LoggerFactory.getLogger(ShareController.class);

    private final TripShareService shareService;

    public ShareController(TripShareService shareService) {
        this.shareService = shareService;
    }

    // ---------- Link chia sẻ (chủ chuyến đi) ----------

    @GetMapping("/trips/{tripId}/share-links")
    public ResponseEntity<?> links(@PathVariable Long tripId, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            // Hai yêu cầu mở modal cùng lúc có thể cùng tạo link: thử lại một lần là đọc được link vừa tạo
            return ResponseEntity.ok(retryOnce(() -> shareService.getLinks(tripId, principal.getName())));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping("/trips/{tripId}/share-links/{role}/reset")
    public ResponseEntity<?> reset(@PathVariable Long tripId, @PathVariable String role, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            return ResponseEntity.ok(shareService.resetLink(tripId, role, principal.getName()));
        } catch (Exception e) {
            return fail(e);
        }
    }

    // ---------- Người cầm link ----------

    @GetMapping("/share/preview/{token}")
    public ResponseEntity<?> preview(@PathVariable String token) {
        try {
            return ResponseEntity.ok(shareService.preview(token));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping("/share/join")
    public ResponseEntity<?> join(@RequestBody JoinRequest request, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            // Bấm đúp nút "Tham gia": yêu cầu thứ hai đụng khóa unique, thử lại sẽ thấy đã là thành viên
            return ResponseEntity.ok(retryOnce(() -> shareService.join(request.token(), principal.getName())));
        } catch (Exception e) {
            return fail(e);
        }
    }

    // ---------- Quản lý bạn đồng hành ----------

    @GetMapping("/trips/{tripId}/members")
    public ResponseEntity<?> members(@PathVariable Long tripId, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            return ResponseEntity.ok(shareService.listMembers(tripId, principal.getName()));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PatchMapping("/trips/{tripId}/members/{memberId}")
    public ResponseEntity<?> changeRole(@PathVariable Long tripId, @PathVariable Long memberId,
                                        @RequestBody RoleRequest request, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            return ResponseEntity.ok(shareService.changeRole(tripId, memberId, request.role(), principal.getName()));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @DeleteMapping("/trips/{tripId}/members/{memberId}")
    public ResponseEntity<?> remove(@PathVariable Long tripId, @PathVariable Long memberId, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            shareService.removeMember(tripId, memberId, principal.getName());
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return fail(e);
        }
    }

    // ---------- Xử lý lỗi ----------

    private static <T> T retryOnce(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException e) {
            return action.get();
        }
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Vui lòng đăng nhập.");
    }

    private ResponseEntity<?> fail(Exception e) {
        if (e instanceof InvitationException ie) {
            return ResponseEntity.status(ie.getStatus()).body(ie.getMessage());
        }
        if (e instanceof DataIntegrityViolationException) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Thao tác vừa được xử lý, vui lòng tải lại.");
        }
        log.error("Lỗi xử lý chia sẻ chuyến đi", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Có lỗi xảy ra, vui lòng thử lại.");
    }
}
