package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.PartnerBusinessHours;
import hcmute.edu.vn.backend.entity.PartnerBusinessMedia;
import hcmute.edu.vn.backend.entity.PartnerBusinessNote;
import hcmute.edu.vn.backend.entity.PartnerSubmission;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.PartnerBusinessHoursRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessMediaRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessNoteRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.PartnerSubmissionRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PartnerSubmissionService {

    private final PartnerBusinessRepository businessRepository;
    private final PartnerSubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final PartnerBusinessMediaRepository mediaRepository;
    private final PartnerBusinessHoursRepository hoursRepository;
    private final PartnerBusinessNoteRepository noteRepository;
    private final PartnerAccessService accessService;
    private final PartnerAuditService auditService;
    // Jackson 3 - Spring Boot 4 tự inject JsonMapper.
    private final JsonMapper jsonMapper;

    /** Đối tác gửi một hồ sơ địa điểm hoàn chỉnh để Admin xét duyệt. */
    @Transactional
    public PartnerSubmission submitBusiness(Long businessId, String email) {
        accessService.requireApprovedPartner(email);

        PartnerBusiness business = businessRepository
                .findByIdAndPartner_User_EmailAndDeletedAtIsNull(businessId, email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy cơ sở kinh doanh hoặc bạn không có quyền quản lý."
                ));

        if ("PENDING_REVIEW".equalsIgnoreCase(business.getVerificationStatus())) {
            throw new IllegalStateException("Địa điểm đang chờ Admin xét duyệt.");
        }

        if (submissionRepository.existsByBusiness_IdAndStatus(businessId, "PENDING")) {
            throw new IllegalStateException(
                    "Cơ sở kinh doanh đã có yêu cầu duyệt đang chờ xử lý."
            );
        }

        User submitter = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy tài khoản gửi yêu cầu."
                ));

        PartnerSubmission submission = new PartnerSubmission();
        submission.setBusiness(business);
        submission.setSubmittedBy(submitter);
        submission.setSubmissionType("BUSINESS_PROFILE");
        submission.setSubmissionData(createBusinessSnapshot(business));
        submission.setStatus("PENDING");

        PartnerSubmission saved = submissionRepository.save(submission);

        business.setVerificationStatus("PENDING_REVIEW");
        business.setPublicationStatus("DRAFT");
        businessRepository.save(business);

        auditService.log(
                email,
                businessId,
                "SUBMIT_FOR_REVIEW",
                "BUSINESS_SUBMISSION",
                saved.getId(),
                null,
                "PENDING"
        );

        return saved;
    }

    /** Xem lịch sử gửi duyệt của một địa điểm thuộc Partner hiện tại. */
    @Transactional(readOnly = true)
    public List<PartnerSubmission> history(Long businessId, String email) {
        accessService.requireApprovedPartner(email);

        businessRepository
                .findByIdAndPartner_User_EmailAndDeletedAtIsNull(businessId, email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy cơ sở kinh doanh hoặc bạn không có quyền quản lý."
                ));

        return submissionRepository.findByBusiness_IdOrderBySubmittedAtDesc(businessId);
    }

    /**
     * Snapshot được đóng băng tại thời điểm Partner gửi duyệt.
     * Không serialize Entity JPA trực tiếp để tránh vòng lặp quan hệ và lazy-loading.
     * Các trường media, hours, notes là dữ liệu con thuộc cùng hồ sơ địa điểm.
     */
    private String createBusinessSnapshot(PartnerBusiness business) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshotVersion", 2);
        snapshot.put("businessId", business.getId());
        snapshot.put("name", business.getName());
        snapshot.put("slug", business.getSlug());
        snapshot.put("businessType", business.getBusinessType());
        snapshot.put("shortDescription", business.getShortDescription());
        snapshot.put("description", business.getDescription());
        snapshot.put("address", business.getAddress());
        snapshot.put("ward", business.getWard());
        snapshot.put("district", business.getDistrict());
        snapshot.put("city", business.getCity());
        snapshot.put("province", business.getProvince());
        snapshot.put("countryCode", business.getCountryCode());
        snapshot.put("latitude", business.getLatitude());
        snapshot.put("longitude", business.getLongitude());
        snapshot.put("phoneNumber", business.getPhoneNumber());
        snapshot.put("email", business.getEmail());
        snapshot.put("websiteUrl", business.getWebsiteUrl());
        snapshot.put("facebookUrl", business.getFacebookUrl());
        snapshot.put("instagramUrl", business.getInstagramUrl());
        snapshot.put("priceLevel", business.getPriceLevel());
        snapshot.put("currency", business.getCurrency());
        snapshot.put("verificationStatus", business.getVerificationStatus());
        snapshot.put("publicationStatus", business.getPublicationStatus());
        snapshot.put("operatingStatus", business.getOperatingStatus());
        snapshot.put("createdAt", business.getCreatedAt());

        snapshot.put("media", mediaRepository
                .findByBusiness_IdOrderBySortOrderAscIdAsc(business.getId())
                .stream()
                .map(this::toMediaSnapshot)
                .toList());

        snapshot.put("hours", hoursRepository
                .findByBusiness_IdOrderByDayOfWeekAsc(business.getId())
                .stream()
                .map(this::toHoursSnapshot)
                .toList());

        snapshot.put("notes", noteRepository
                .findByBusiness_IdOrderByCreatedAtDesc(business.getId())
                .stream()
                .map(this::toNoteSnapshot)
                .toList());

        try {
            return jsonMapper.writeValueAsString(snapshot);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Không thể tạo JSON snapshot gửi Admin duyệt.",
                    exception
            );
        }
    }

    private Map<String, Object> toMediaSnapshot(PartnerBusinessMedia media) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", media.getId());
        item.put("mediaType", media.getMediaType());
        item.put("mediaUrl", media.getMediaUrl());
        item.put("thumbnailUrl", media.getThumbnailUrl());
        item.put("caption", media.getCaption());
        item.put("cover", media.getCover());
        item.put("sortOrder", media.getSortOrder());
        item.put("createdAt", media.getCreatedAt());
        return item;
    }

    private Map<String, Object> toHoursSnapshot(PartnerBusinessHours hours) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", hours.getId());
        item.put("dayOfWeek", hours.getDayOfWeek());
        item.put("openTime", hours.getOpenTime());
        item.put("closeTime", hours.getCloseTime());
        item.put("closed", hours.getClosed());
        return item;
    }

    private Map<String, Object> toNoteSnapshot(PartnerBusinessNote note) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", note.getId());
        item.put("title", note.getTitle());
        item.put("content", note.getContent());
        item.put("noteType", note.getNoteType());
        item.put("active", note.getActive());
        item.put("createdAt", note.getCreatedAt());
        item.put("updatedAt", note.getUpdatedAt());
        return item;
    }
}
