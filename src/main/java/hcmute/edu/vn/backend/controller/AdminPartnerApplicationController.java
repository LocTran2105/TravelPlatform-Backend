package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.AdminReviewRequest;
import hcmute.edu.vn.backend.service.AdminPartnerApplicationService;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/admin/partner-applications")
@RequiredArgsConstructor
public class AdminPartnerApplicationController {

    private final AdminPartnerApplicationService applicationService;

    /**
     * Danh sách đơn đăng ký đối tác.
     *
     * Mặc định chỉ xem đơn PENDING.
     */
    @GetMapping
    public ResponseEntity<?> getApplications(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        try {

            return ResponseEntity.ok(
                    applicationService.getApplications(
                            status,
                            page,
                            size
                    )
            );

        } catch (IllegalArgumentException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Chi tiết đơn đăng ký.
     */
    @GetMapping("/{applicationId}")
    public ResponseEntity<?> getApplication(
            @PathVariable Long applicationId
    ) {
        try {

            return ResponseEntity.ok(
                    applicationService.getApplication(applicationId)
            );

        } catch (IllegalArgumentException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Duyệt đơn đăng ký Partner.
     */
    @PutMapping("/{applicationId}/approve")
    public ResponseEntity<?> approve(
            @PathVariable Long applicationId,
            @RequestBody(required = false) AdminReviewRequest request,
            Principal principal
    ) {
        try {

            String note = request == null
                    ? null
                    : request.note();

            return ResponseEntity.ok(
                    applicationService.approve(
                            applicationId,
                            note,
                            principal.getName()
                    )
            );

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Từ chối đơn đăng ký Partner.
     */
    @PutMapping("/{applicationId}/reject")
    public ResponseEntity<?> reject(
            @PathVariable Long applicationId,
            @RequestBody AdminReviewRequest request,
            Principal principal
    ) {
        try {

            return ResponseEntity.ok(
                    applicationService.reject(
                            applicationId,
                            request == null ? null : request.note(),
                            principal.getName()
                    )
            );

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }
}