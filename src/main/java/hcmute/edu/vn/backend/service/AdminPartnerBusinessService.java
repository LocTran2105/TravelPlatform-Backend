package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.AdminBusinessLocationRequest;
import hcmute.edu.vn.backend.dto.AdminBusinessApprovalRequest;
import hcmute.edu.vn.backend.dto.AdminOsmPlaceCandidateResponse;
import hcmute.edu.vn.backend.dto.AdminBusinessOperatingStatusRequest;
import hcmute.edu.vn.backend.dto.AdminBusinessVisibilityRequest;
import hcmute.edu.vn.backend.dto.PartnerBusinessAdminResponse;
import hcmute.edu.vn.backend.dto.PartnerSubmissionAdminResponse;
import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.PartnerBusinessMedia;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.entity.PartnerSubmission;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessMediaRepository;
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

    private final PartnerPlaceLinkService partnerPlaceLinkService;

    private final OsmPlaceDiscoveryService osmPlaceDiscoveryService;

    private final PartnerBusinessMediaRepository mediaRepository;

    /**
     * Admin xem danh sách yêu cầu duyệt Business.
     */
    @Transactional(readOnly = true)
    public List<PartnerSubmissionAdminResponse> getSubmissions(
            String status
    ) {
        // Khi Admin chọn "Tất cả", không gắn bộ lọc trạng thái.
        if (status == null || status.isBlank()) {
            return submissionRepository
                    .findAll(Sort.by(Sort.Direction.ASC, "submittedAt"))
                    .stream()
                    .map(PartnerSubmissionAdminResponse::from)
                    .toList();
        }

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
     * Tìm các OSM POI gần vị trí Partner để Admin kiểm tra trên bản đồ.
     * Discovery chỉ lưu OSM records; nó không được phép tự gắn Business.
     */
    public List<AdminOsmPlaceCandidateResponse> getOsmCandidates(
            Long submissionId,
            int radiusMeters,
            String adminEmail) {

        requireAdmin(adminEmail);
        PartnerSubmission submission = submissionRepository.findByIdWithBusiness(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu duyệt."));
        ensurePending(submission);
        PartnerBusiness business = submission.getBusiness();
        if (business == null || business.getDeletedAt() != null) {
            throw new IllegalStateException("Business không tồn tại hoặc đã bị xóa.");
        }
        if (business.getLatitude() == null || business.getLongitude() == null) {
            throw new IllegalStateException("Business chưa khai báo latitude/longitude. Partner cần bổ sung tọa độ trước khi Admin kiểm tra bản đồ.");
        }

        String businessType = business.getBusinessType() == null
                ? "" : business.getBusinessType().trim().toUpperCase(Locale.ROOT);
        Set<String> osmSearchableTypes = Set.of(
                "HOTEL", "HOMESTAY", "RESORT", "HOSTEL", "APARTMENT",
                "RESTAURANT", "EATERY", "CAFE", "COFFEE_SHOP",
                "ATTRACTION", "TOURIST_SITE"
        );
        // Avoid showing unrelated attractions as candidates for shops, spas, or OTHER businesses.
        if (!osmSearchableTypes.contains(businessType)) {
            return List.of();
        }

        List<Place> candidates = osmPlaceDiscoveryService.discoverNearbyPlacesForAdmin(
                business.getCity(), business.getLatitude(), business.getLongitude(),
                businessType, radiusMeters, 50);

        return candidates.stream()
                .filter(place -> place.getPartnerBusinessId() == null
                        || place.getPartnerBusinessId().equals(business.getId()))
                .map(place -> new AdminOsmPlaceCandidateResponse(
                        place.getId(), place.getProviderPlaceId(), place.getName(),
                        place.getAddress(), place.getPlaceType(), place.getLatitude(),
                        place.getLongitude(), distanceMeters(
                        business.getLatitude(), business.getLongitude(),
                        place.getLatitude(), place.getLongitude())))
                .sorted((a, b) -> Double.compare(
                        a.distanceMeters() == null ? Double.MAX_VALUE : a.distanceMeters(),
                        b.distanceMeters() == null ? Double.MAX_VALUE : b.distanceMeters()))
                .toList();
    }

    /**
     * Admin duyệt Business và BẮT BUỘC xác nhận nguồn Place trước khi công khai.
     * Việc gắn Place và cập nhật trạng thái nằm trong cùng một transaction.
     */
    @Transactional
    public PartnerSubmissionAdminResponse approveSubmission(
            Long submissionId,
            AdminBusinessApprovalRequest request,
            String adminEmail) {

        User admin = requireAdmin(adminEmail);
        if (request == null) {
            throw new IllegalArgumentException("Thiếu lựa chọn địa điểm OSM/PARTNER của Admin.");
        }

        PartnerSubmission submission = submissionRepository.findByIdForReview(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu duyệt."));
        ensurePending(submission);

        PartnerBusiness business = submission.getBusiness();
        if (business == null || business.getDeletedAt() != null) {
            throw new IllegalStateException("Cơ sở kinh doanh đã bị xóa hoặc không còn tồn tại.");
        }
        if (!"BUSINESS_PROFILE".equalsIgnoreCase(submission.getSubmissionType())) {
            throw new IllegalArgumentException("Loại yêu cầu này chưa được hỗ trợ duyệt.");
        }

        String selectedProvider = request.placeProvider() == null
                ? "" : request.placeProvider().trim().toUpperCase(Locale.ROOT);
        Place confirmedPlace;
        if ("OSM".equals(selectedProvider)) {
            if (request.placeId() == null) {
                throw new IllegalArgumentException("Admin phải chọn một OSM POI trên bản đồ.");
            }
            confirmedPlace = partnerPlaceLinkService.confirmOsmPlace(business.getId(), request.placeId());
        } else if ("PARTNER".equals(selectedProvider)) {
            confirmedPlace = partnerPlaceLinkService.createPartnerPlace(
                    business.getId(), request.latitude(), request.longitude());
        } else {
            throw new IllegalArgumentException("placeProvider phải là OSM hoặc PARTNER.");
        }

        LocalDateTime now = LocalDateTime.now();
        String oldVerificationStatus = business.getVerificationStatus();
        String oldPublicationStatus = business.getPublicationStatus();

        business.setVerificationStatus("APPROVED");
        business.setPublicationStatus("PUBLISHED");
        business.setVerifiedBy(admin);
        business.setVerifiedAt(now);
        business.setRejectionReason(null);
        businessRepository.save(business);

        // Ảnh nằm trong snapshot mà Admin vừa xem; việc duyệt Business đồng thời duyệt các media đó.
        List<PartnerBusinessMedia> mediaItems = mediaRepository
                .findByBusiness_IdOrderBySortOrderAscIdAsc(business.getId());
        for (PartnerBusinessMedia media : mediaItems) {
            if (media == null || media.getMediaType() == null
                    || !"IMAGE".equalsIgnoreCase(media.getMediaType())
                    || media.getModerationStatus() == null
                    || !"PENDING".equalsIgnoreCase(media.getModerationStatus())) {
                continue;
            }
            media.setModerationStatus("APPROVED");
            media.setReviewedById(admin.getId());
            media.setReviewedAt(now);
        }
        mediaRepository.saveAll(mediaItems);

        submission.setStatus("APPROVED");
        submission.setReviewedBy(admin);
        submission.setReviewedAt(now);
        submission.setReviewNote(trimToNull(request.note()));
        PartnerSubmission saved = submissionRepository.save(submission);

        auditService.log(
                adminEmail,
                business.getId(),
                "ADMIN_APPROVE_BUSINESS",
                "PARTNER_BUSINESS",
                business.getId(),
                oldVerificationStatus + "/" + oldPublicationStatus,
                "APPROVED/PUBLISHED; PLACE=" + confirmedPlace.getProvider() + ":" + confirmedPlace.getId()
        );

        return PartnerSubmissionAdminResponse.from(saved);
    }

    private static Double distanceMeters(BigDecimal lat1, BigDecimal lon1,
                                         BigDecimal lat2, BigDecimal lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return null;
        double p1 = Math.toRadians(lat1.doubleValue());
        double p2 = Math.toRadians(lat2.doubleValue());
        double dLat = p2 - p1;
        double dLon = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
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
