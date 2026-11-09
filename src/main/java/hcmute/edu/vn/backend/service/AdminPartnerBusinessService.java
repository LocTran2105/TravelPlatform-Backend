package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.AdminBusinessLocationRequest;
import hcmute.edu.vn.backend.dto.AdminBusinessOperatingStatusRequest;
import hcmute.edu.vn.backend.dto.AdminBusinessVisibilityRequest;
import hcmute.edu.vn.backend.dto.PartnerBusinessAdminResponse;
import hcmute.edu.vn.backend.dto.PartnerSubmissionAdminResponse;
import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.PartnerSubmission;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.PartnerSubmissionRepository;
import hcmute.edu.vn.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminPartnerBusinessService {

    private static final Set<String> PUBLICATION_STATUSES = Set.of(
            "PUBLISHED",
            "HIDDEN",
            "DRAFT"
    );

    private static final Set<String> OPERATING_STATUSES = Set.of(
            "OPEN",
            "TEMPORARILY_CLOSED",
            "CLOSED_PERMANENTLY"
    );

    private final PartnerBusinessRepository businessRepository;

    private final PartnerSubmissionRepository submissionRepository;

    private final UserRepository userRepository;

    private final PartnerAuditService auditService;

    /**
     * Admin xem danh sách yêu cầu duyệt Business.
     */
    @Transactional(readOnly = true)
    public List<PartnerSubmissionAdminResponse> getSubmissions(
            String status
    ) {

        String normalizedStatus = normalizeSubmissionStatus(status);

        return submissionRepository
                .findByStatusOrderBySubmittedAtAsc(normalizedStatus)
                .stream()
                .map(PartnerSubmissionAdminResponse::from)
                .toList();
    }

    /**
     * Admin xem chi tiết yêu cầu duyệt.
     */
    @Transactional(readOnly = true)
    public PartnerSubmissionAdminResponse getSubmission(
            Long submissionId
    ) {

        PartnerSubmission submission =
                submissionRepository.findById(submissionId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy yêu cầu duyệt."
                                )
                        );

        return PartnerSubmissionAdminResponse.from(submission);
    }

    /**
     * Admin duyệt thông tin Business.
     */
    @Transactional
    public PartnerSubmissionAdminResponse approveSubmission(
            Long submissionId,
            String reviewNote,
            String adminEmail
    ) {

        User admin = requireAdmin(adminEmail);

        PartnerSubmission submission =
                submissionRepository.findByIdForReview(submissionId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy yêu cầu duyệt."
                                )
                        );

        ensurePending(submission);

        PartnerBusiness business = submission.getBusiness();

        if (business.getDeletedAt() != null) {
            throw new IllegalStateException(
                    "Cơ sở kinh doanh đã bị xóa."
            );
        }

        if (!"BUSINESS_PROFILE".equalsIgnoreCase(
                submission.getSubmissionType()
        )) {
            throw new IllegalArgumentException(
                    "Loại yêu cầu này chưa được hỗ trợ duyệt."
            );
        }

        LocalDateTime now = LocalDateTime.now();

        String oldVerificationStatus =
                business.getVerificationStatus();

        String oldPublicationStatus =
                business.getPublicationStatus();

        // Cập nhật Business.
        business.setVerificationStatus("APPROVED");
        business.setPublicationStatus("PUBLISHED");
        business.setVerifiedBy(admin);
        business.setVerifiedAt(now);
        business.setRejectionReason(null);

        businessRepository.save(business);

        // Cập nhật Submission.
        submission.setStatus("APPROVED");
        submission.setReviewedBy(admin);
        submission.setReviewedAt(now);
        submission.setReviewNote(trimToNull(reviewNote));

        PartnerSubmission saved =
                submissionRepository.save(submission);

        // Audit.
        auditService.log(
                adminEmail,
                business.getId(),
                "ADMIN_APPROVE_BUSINESS",
                "PARTNER_BUSINESS",
                business.getId(),
                oldVerificationStatus + "/" + oldPublicationStatus,
                "APPROVED/PUBLISHED"
        );

        return PartnerSubmissionAdminResponse.from(saved);
    }

    /**
     * Admin từ chối thông tin Business.
     */
    @Transactional
    public PartnerSubmissionAdminResponse rejectSubmission(
            Long submissionId,
            String reviewNote,
            String adminEmail
    ) {

        User admin = requireAdmin(adminEmail);

        PartnerSubmission submission =
                submissionRepository.findByIdForReview(submissionId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Không tìm thấy yêu cầu duyệt."
                                )
                        );

        ensurePending(submission);

        if (reviewNote == null || reviewNote.isBlank()) {
            throw new IllegalArgumentException(
                    "Vui lòng nhập lý do từ chối."
            );
        }

        PartnerBusiness business = submission.getBusiness();

        LocalDateTime now = LocalDateTime.now();

        business.setVerificationStatus("REJECTED");
        business.setPublicationStatus("DRAFT");
        business.setVerifiedBy(admin);
        business.setVerifiedAt(now);
        business.setRejectionReason(reviewNote.trim());

        businessRepository.save(business);

        submission.setStatus("REJECTED");
        submission.setReviewedBy(admin);
        submission.setReviewedAt(now);
        submission.setReviewNote(reviewNote.trim());

        PartnerSubmission saved =
                submissionRepository.save(submission);

        auditService.log(
                adminEmail,
                business.getId(),
                "ADMIN_REJECT_BUSINESS",
                "PARTNER_BUSINESS",
                business.getId(),
                "PENDING_REVIEW",
                "REJECTED"
        );

        return PartnerSubmissionAdminResponse.from(saved);
    }

    /**
     * Admin tìm kiếm và lọc Business.
     */
    @Transactional(readOnly = true)
    public Page<PartnerBusinessAdminResponse> getBusinesses(
            String verificationStatus,
            String publicationStatus,
            String businessType,
            String keyword,
            int page,
            int size
    ) {

        validatePagination(page, size);

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        return businessRepository.searchForAdmin(
                        normalizeOptional(verificationStatus),
                        normalizeOptional(publicationStatus),
                        normalizeOptional(businessType),
                        trimToNull(keyword),
                        pageable
                )
                .map(PartnerBusinessAdminResponse::from);
    }

    /**
     * Admin thay đổi trạng thái hiển thị.
     */
    @Transactional
    public PartnerBusinessAdminResponse updateVisibility(
            Long businessId,
            AdminBusinessVisibilityRequest request,
            String adminEmail
    ) {

        requireAdmin(adminEmail);

        if (request == null
                || request.publicationStatus() == null
                || request.publicationStatus().isBlank()) {

            throw new IllegalArgumentException(
                    "Trạng thái hiển thị không được để trống."
            );
        }

        PartnerBusiness business = getBusiness(businessId);

        String newStatus = request.publicationStatus()
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!PUBLICATION_STATUSES.contains(newStatus)) {
            throw new IllegalArgumentException(
                    "Trạng thái hiển thị không hợp lệ."
            );
        }

        if ("PUBLISHED".equals(newStatus)
                && !"APPROVED".equalsIgnoreCase(
                business.getVerificationStatus()
        )) {

            throw new IllegalStateException(
                    "Chỉ Business đã được duyệt mới được công khai."
            );
        }

        String oldStatus = business.getPublicationStatus();

        business.setPublicationStatus(newStatus);

        businessRepository.save(business);

        auditService.log(
                adminEmail,
                businessId,
                "UPDATE_VISIBILITY",
                "PARTNER_BUSINESS",
                businessId,
                oldStatus,
                newStatus
        );

        return PartnerBusinessAdminResponse.from(business);
    }

    /**
     * Admin cập nhật trạng thái kinh doanh.
     */
    @Transactional
    public PartnerBusinessAdminResponse updateOperatingStatus(
            Long businessId,
            AdminBusinessOperatingStatusRequest request,
            String adminEmail
    ) {

        requireAdmin(adminEmail);

        if (request == null
                || request.operatingStatus() == null
                || request.operatingStatus().isBlank()) {

            throw new IllegalArgumentException(
                    "Trạng thái kinh doanh không được để trống."
            );
        }

        PartnerBusiness business = getBusiness(businessId);

        String newStatus = request.operatingStatus()
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!OPERATING_STATUSES.contains(newStatus)) {
            throw new IllegalArgumentException(
                    "Trạng thái kinh doanh không hợp lệ."
            );
        }

        String oldStatus = business.getOperatingStatus();

        business.setOperatingStatus(newStatus);

        businessRepository.save(business);

        auditService.log(
                adminEmail,
                businessId,
                "UPDATE_OPERATING_STATUS",
                "PARTNER_BUSINESS",
                businessId,
                oldStatus,
                newStatus
        );

        return PartnerBusinessAdminResponse.from(business);
    }

    /**
     * Admin điều chỉnh vị trí và địa chỉ Business.
     */
    @Transactional
    public PartnerBusinessAdminResponse updateLocation(
            Long businessId,
            AdminBusinessLocationRequest request,
            String adminEmail
    ) {

        requireAdmin(adminEmail);

        if (request == null) {
            throw new IllegalArgumentException(
                    "Dữ liệu vị trí không hợp lệ."
            );
        }

        PartnerBusiness business = getBusiness(businessId);

        if (request.address() == null
                || request.address().isBlank()
                || request.city() == null
                || request.city().isBlank()) {

            throw new IllegalArgumentException(
                    "Địa chỉ và thành phố không được để trống."
            );
        }

        BigDecimal latitude = request.latitude();
        BigDecimal longitude = request.longitude();

        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException(
                    "Latitude và longitude phải được nhập cùng nhau."
            );
        }

        if (latitude != null
                && (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0)) {

            throw new IllegalArgumentException(
                    "Latitude không hợp lệ."
            );
        }

        if (longitude != null
                && (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0)) {

            throw new IllegalArgumentException(
                    "Longitude không hợp lệ."
            );
        }

        String oldLocation = business.getAddress()
                + ", "
                + business.getCity();

        business.setAddress(request.address().trim());
        business.setWard(trimToNull(request.ward()));
        business.setDistrict(trimToNull(request.district()));
        business.setCity(request.city().trim());
        business.setProvince(trimToNull(request.province()));
        business.setLatitude(latitude);
        business.setLongitude(longitude);

        PartnerBusiness saved =
                businessRepository.save(business);

        String newLocation = saved.getAddress()
                + ", "
                + saved.getCity();

        auditService.log(
                adminEmail,
                businessId,
                "ADMIN_UPDATE_LOCATION",
                "PARTNER_BUSINESS",
                businessId,
                oldLocation,
                newLocation
        );

        return PartnerBusinessAdminResponse.from(saved);
    }

    private PartnerBusiness getBusiness(Long businessId) {

        return businessRepository.findById(businessId)
                .filter(business -> business.getDeletedAt() == null)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy cơ sở kinh doanh."
                        )
                );
    }

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

    private void ensurePending(PartnerSubmission submission) {

        if (!"PENDING".equalsIgnoreCase(submission.getStatus())) {
            throw new IllegalStateException(
                    "Yêu cầu này đã được xử lý trước đó."
            );
        }
    }

    private String normalizeSubmissionStatus(String status) {

        if (status == null || status.isBlank()) {
            return "PENDING";
        }

        String value = status.trim()
                .toUpperCase(Locale.ROOT);

        if (!Set.of("PENDING", "APPROVED", "REJECTED")
                .contains(value)) {

            throw new IllegalArgumentException(
                    "Trạng thái yêu cầu không hợp lệ."
            );
        }

        return value;
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }

    private void validatePagination(int page, int size) {

        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "Tham số phân trang không hợp lệ."
            );
        }
    }
}