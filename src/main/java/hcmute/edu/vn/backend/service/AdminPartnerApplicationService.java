package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PartnerApplicationAdminResponse;
import hcmute.edu.vn.backend.entity.PartnerApplication;
import hcmute.edu.vn.backend.entity.PartnerProfile;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.PartnerApplicationRepository;
import hcmute.edu.vn.backend.repository.PartnerProfileRepository;
import hcmute.edu.vn.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AdminPartnerApplicationService {

    private final PartnerApplicationRepository applicationRepository;

    private final PartnerProfileRepository partnerProfileRepository;

    private final UserRepository userRepository;

    private final PartnerAuditService auditService;

    /**
     * Admin xem danh sách đơn đăng ký đối tác.
     */
    @Transactional(readOnly = true)
    public Page<PartnerApplicationAdminResponse> getApplications(
            String status,
            int page,
            int size
    ) {

        validatePagination(page, size);

        String normalizedStatus = normalizeStatus(status);

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        return applicationRepository
                .findByVerificationStatus(
                        normalizedStatus,
                        pageable
                )
                .map(PartnerApplicationAdminResponse::from);
    }

    /**
     * Admin xem chi tiết đơn đăng ký.
     */
    @Transactional(readOnly = true)
    public PartnerApplicationAdminResponse getApplication(
            Long applicationId
    ) {

        PartnerApplication application =
                applicationRepository.findById(applicationId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy đơn đăng ký đối tác."
                                )
                        );

        return PartnerApplicationAdminResponse.from(application);
    }

    /**
     * Admin duyệt đơn đăng ký Partner.
     */
    @Transactional
    public PartnerApplicationAdminResponse approve(
            Long applicationId,
            String adminNote,
            String adminEmail
    ) {

        User admin = requireAdmin(adminEmail);

        // Khóa bản ghi trong transaction.
        PartnerApplication application =
                applicationRepository.findByIdForReview(applicationId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy đơn đăng ký đối tác."
                                )
                        );

        ensurePending(application);

        User applicant = application.getUser();

        if (!"ACTIVE".equalsIgnoreCase(applicant.getStatus())) {
            throw new IllegalStateException(
                    "Tài khoản người đăng ký hiện không hoạt động."
            );
        }

        if (!"ROLE_TRAVELLER".equalsIgnoreCase(applicant.getRole())) {
            throw new IllegalStateException(
                    "Tài khoản không còn thuộc nhóm Traveller."
            );
        }

        if (partnerProfileRepository.existsByUser_Id(
                applicant.getId()
        )) {

            throw new IllegalStateException(
                    "Tài khoản đã có hồ sơ đối tác."
            );
        }

        LocalDateTime now = LocalDateTime.now();

        // 1. Tạo PartnerProfile.
        PartnerProfile profile = new PartnerProfile();

        profile.setUser(applicant);

        profile.setDisplayName(
                application.getDisplayName()
        );

        profile.setLegalName(
                application.getLegalName()
        );

        profile.setTaxCode(
                application.getTaxCode()
        );

        profile.setContactPerson(
                application.getContactPerson()
        );

        profile.setContactEmail(
                application.getContactEmail()
        );

        profile.setContactPhone(
                application.getContactPhone()
        );

        profile.setBusinessAddress(
                application.getBusinessAddress()
        );

        profile.setVerificationStatus("APPROVED");
        profile.setVerifiedBy(admin);
        profile.setVerifiedAt(now);
        profile.setRejectionReason(null);

        partnerProfileRepository.save(profile);

        // 2. Cấp ROLE_PARTNER.
        applicant.setRole("ROLE_PARTNER");

        userRepository.save(applicant);

        // 3. Cập nhật trạng thái đơn đăng ký.
        application.setVerificationStatus("APPROVED");
        application.setReviewedBy(admin);
        application.setReviewedAt(now);
        application.setAdminNote(trimToNull(adminNote));

        PartnerApplication saved =
                applicationRepository.save(application);

        // 4. Ghi audit log.
        auditService.log(
                adminEmail,
                null,
                "APPROVE_PARTNER_APPLICATION",
                "PARTNER_APPLICATION",
                applicationId,
                "PENDING",
                "APPROVED"
        );

        return PartnerApplicationAdminResponse.from(saved);
    }

    /**
     * Admin từ chối đơn đăng ký Partner.
     *
     * User vẫn giữ ROLE_TRAVELLER và có thể đăng ký lại.
     */
    @Transactional
    public PartnerApplicationAdminResponse reject(
            Long applicationId,
            String adminNote,
            String adminEmail
    ) {

        User admin = requireAdmin(adminEmail);

        PartnerApplication application =
                applicationRepository.findByIdForReview(applicationId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy đơn đăng ký đối tác."
                                )
                        );

        ensurePending(application);

        if (adminNote == null || adminNote.isBlank()) {
            throw new IllegalArgumentException(
                    "Vui lòng nhập lý do từ chối đơn đăng ký."
            );
        }

        application.setVerificationStatus("REJECTED");
        application.setReviewedBy(admin);
        application.setReviewedAt(LocalDateTime.now());
        application.setAdminNote(adminNote.trim());

        PartnerApplication saved =
                applicationRepository.save(application);

        auditService.log(
                adminEmail,
                null,
                "REJECT_PARTNER_APPLICATION",
                "PARTNER_APPLICATION",
                applicationId,
                "PENDING",
                "REJECTED"
        );

        return PartnerApplicationAdminResponse.from(saved);
    }

    /**
     * Kiểm tra tài khoản Admin.
     */
    private User requireAdmin(String email) {

        User admin = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy tài khoản Admin."
                        )
                );

        if (!"ACTIVE".equalsIgnoreCase(admin.getStatus())
                || !"ROLE_ADMIN".equalsIgnoreCase(admin.getRole())) {

            throw new IllegalStateException(
                    "Bạn không có quyền thực hiện thao tác này."
            );
        }

        return admin;
    }

    /**
     * Chỉ được xử lý đơn đang PENDING.
     */
    private void ensurePending(
            PartnerApplication application
    ) {

        if (!"PENDING".equalsIgnoreCase(
                application.getVerificationStatus()
        )) {

            throw new IllegalStateException(
                    "Đơn đăng ký đã được xử lý trước đó."
            );
        }
    }

    private String normalizeStatus(String status) {

        if (status == null || status.isBlank()) {
            return "PENDING";
        }

        String normalized = status.trim()
                .toUpperCase(Locale.ROOT);

        if (!normalized.equals("PENDING")
                && !normalized.equals("APPROVED")
                && !normalized.equals("REJECTED")) {

            throw new IllegalArgumentException(
                    "Trạng thái đơn đăng ký không hợp lệ."
            );
        }

        return normalized;
    }

    private void validatePagination(int page, int size) {

        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "Tham số phân trang không hợp lệ."
            );
        }
    }

    private String trimToNull(String value) {

        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}