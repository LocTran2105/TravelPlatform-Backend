package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.AdminBusinessLocationRequest;
import hcmute.edu.vn.backend.dto.AdminBusinessOperatingStatusRequest;
import hcmute.edu.vn.backend.dto.AdminBusinessVisibilityRequest;
import hcmute.edu.vn.backend.dto.AdminReviewRequest;
import hcmute.edu.vn.backend.service.AdminPartnerBusinessService;

import lombok.RequiredArgsConstructor;

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
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminPartnerBusinessController {

    private final AdminPartnerBusinessService businessService;

    /**
     * Danh sách Business chờ duyệt.
     */
    @GetMapping("/partner-submissions")
    public ResponseEntity<?> getSubmissions(
            @RequestParam(defaultValue = "PENDING") String status
    ) {
        try {

            return ResponseEntity.ok(
                    businessService.getSubmissions(status)
            );

        } catch (IllegalArgumentException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Chi tiết yêu cầu duyệt.
     */
    @GetMapping("/partner-submissions/{submissionId}")
    public ResponseEntity<?> getSubmission(
            @PathVariable Long submissionId
    ) {
        try {

            return ResponseEntity.ok(
                    businessService.getSubmission(submissionId)
            );

        } catch (IllegalArgumentException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Admin duyệt Business.
     */
    @PutMapping("/partner-submissions/{submissionId}/approve")
    public ResponseEntity<?> approveSubmission(
            @PathVariable Long submissionId,
            @RequestBody(required = false) AdminReviewRequest request,
            Principal principal
    ) {
        try {

            return ResponseEntity.ok(
                    businessService.approveSubmission(
                            submissionId,
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

    /**
     * Admin từ chối Business.
     */
    @PutMapping("/partner-submissions/{submissionId}/reject")
    public ResponseEntity<?> rejectSubmission(
            @PathVariable Long submissionId,
            @RequestBody AdminReviewRequest request,
            Principal principal
    ) {
        try {

            return ResponseEntity.ok(
                    businessService.rejectSubmission(
                            submissionId,
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

    /**
     * Admin tìm kiếm Business.
     */
    @GetMapping("/businesses")
    public ResponseEntity<?> getBusinesses(
            @RequestParam(required = false) String verificationStatus,
            @RequestParam(required = false) String publicationStatus,
            @RequestParam(required = false) String businessType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        try {

            return ResponseEntity.ok(
                    businessService.getBusinesses(
                            verificationStatus,
                            publicationStatus,
                            businessType,
                            keyword,
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
     * Admin thay đổi trạng thái hiển thị.
     */
    @PutMapping("/businesses/{businessId}/visibility")
    public ResponseEntity<?> updateVisibility(
            @PathVariable Long businessId,
            @RequestBody AdminBusinessVisibilityRequest request,
            Principal principal
    ) {
        try {

            return ResponseEntity.ok(
                    businessService.updateVisibility(
                            businessId,
                            request,
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
     * Admin thay đổi trạng thái hoạt động.
     */
    @PutMapping("/businesses/{businessId}/operating-status")
    public ResponseEntity<?> updateOperatingStatus(
            @PathVariable Long businessId,
            @RequestBody AdminBusinessOperatingStatusRequest request,
            Principal principal
    ) {
        try {

            return ResponseEntity.ok(
                    businessService.updateOperatingStatus(
                            businessId,
                            request,
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
     * Admin chỉnh sửa địa chỉ và tọa độ.
     */
    @PutMapping("/businesses/{businessId}/location")
    public ResponseEntity<?> updateLocation(
            @PathVariable Long businessId,
            @RequestBody AdminBusinessLocationRequest request,
            Principal principal
    ) {
        try {

            return ResponseEntity.ok(
                    businessService.updateLocation(
                            businessId,
                            request,
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