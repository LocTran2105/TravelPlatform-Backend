package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.InvitationDtos.InviteRequest;
import hcmute.edu.vn.backend.dto.InvitationDtos.TokenRequest;
import hcmute.edu.vn.backend.service.InvitationException;
import hcmute.edu.vn.backend.service.TripInvitationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * Lời mời tham gia chuyến đi.
 *   Công khai : GET  /api/invitations/preview/{token}
 *   Cần đăng nhập: các API còn lại
 * Lỗi trả về là chuỗi thông báo (khớp cách frontend đang đọc error.response.data).
 */
@RestController
@RequestMapping("/api")
public class InvitationController {

    private static final Logger log = LoggerFactory.getLogger(InvitationController.class);

    private final TripInvitationService invitationService;

    public InvitationController(TripInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    // ---------- Phía người được mời ----------

    @GetMapping("/invitations/preview/{token}")
    public ResponseEntity<?> preview(@PathVariable String token) {
        try {
            return ResponseEntity.ok(invitationService.preview(token));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @GetMapping("/invitations/mine")
    public ResponseEntity<?> mine(Principal principal) {
        if (principal == null) return unauthorized();
        try {
            return ResponseEntity.ok(invitationService.listMine(principal.getName()));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping("/invitations/accept")
    public ResponseEntity<?> accept(@RequestBody TokenRequest request, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            return ResponseEntity.ok(invitationService.accept(request.token(), principal.getName()));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping("/invitations/decline")
    public ResponseEntity<?> decline(@RequestBody TokenRequest request, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            return ResponseEntity.ok(invitationService.decline(request.token(), principal.getName()));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping("/invitations/{id}/accept")
    public ResponseEntity<?> acceptById(@PathVariable Long id, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            return ResponseEntity.ok(invitationService.acceptById(id, principal.getName()));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping("/invitations/{id}/decline")
    public ResponseEntity<?> declineById(@PathVariable Long id, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            return ResponseEntity.ok(invitationService.declineById(id, principal.getName()));
        } catch (Exception e) {
            return fail(e);
        }
    }

    // ---------- Phía chuyến đi ----------

    @GetMapping("/trips/{tripId}/invitations")
    public ResponseEntity<?> listForTrip(@PathVariable Long tripId, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            return ResponseEntity.ok(invitationService.listForTrip(tripId, principal.getName()));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping("/trips/{tripId}/invitations")
    public ResponseEntity<?> invite(@PathVariable Long tripId, @RequestBody InviteRequest request, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            return ResponseEntity.ok(invitationService.inviteByOwner(tripId, principal.getName(), request));
        } catch (Exception e) {
            return fail(e);
        }
    }

    @DeleteMapping("/trips/{tripId}/invitations/{invitationId}")
    public ResponseEntity<?> revoke(@PathVariable Long tripId, @PathVariable Long invitationId, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            invitationService.revoke(tripId, invitationId, principal.getName());
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @PostMapping("/trips/{tripId}/invitations/{invitationId}/resend")
    public ResponseEntity<?> resend(@PathVariable Long tripId, @PathVariable Long invitationId, Principal principal) {
        if (principal == null) return unauthorized();
        try {
            return ResponseEntity.ok(invitationService.resend(tripId, invitationId, principal.getName()));
        } catch (Exception e) {
            return fail(e);
        }
    }

    // ---------- Xử lý lỗi ----------

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Vui lòng đăng nhập.");
    }

    private ResponseEntity<?> fail(Exception e) {
        if (e instanceof InvitationException ie) {
            return ResponseEntity.status(ie.getStatus()).body(ie.getMessage());
        }
        if (e instanceof DataIntegrityViolationException) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Lời mời đã tồn tại hoặc vừa được xử lý.");
        }
        log.error("Lỗi xử lý lời mời", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Có lỗi xảy ra, vui lòng thử lại.");
    }
}
