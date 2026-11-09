package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.PartnerSubmission;
import hcmute.edu.vn.backend.entity.User;

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

    private final PartnerAccessService accessService;

    private final PartnerAuditService auditService;

    // Jackson 3 - Spring Boot 4 tự inject JsonMapper.
    private final JsonMapper jsonMapper;

    /**
     * Đối tác gửi thông tin Business để Admin duyệt.
     */
    @Transactional
    public PartnerSubmission submitBusiness(
            Long businessId,
            String email
    ) {

        // Kiểm tra Partner đã được Admin xác minh.
        accessService.requireApprovedPartner(email);

        // Kiểm tra Business thuộc Partner hiện tại.
        PartnerBusiness business = businessRepository
                .findByIdAndPartner_User_EmailAndDeletedAtIsNull(
                        businessId,
                        email
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy cơ sở kinh doanh "
                                        + "hoặc bạn không có quyền quản lý."
                        )
                );

        // Không cho phép gửi nhiều yêu cầu cùng lúc.
        if (submissionRepository.existsByBusiness_IdAndStatus(
                businessId,
                "PENDING"
        )) {

            throw new IllegalStateException(
                    "Cơ sở kinh doanh đã có yêu cầu duyệt "
                            + "đang chờ xử lý."
            );
        }

        // Tìm tài khoản gửi yêu cầu.
        User submitter = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy tài khoản gửi yêu cầu."
                        )
                );

        // Tạo yêu cầu kiểm duyệt.
        PartnerSubmission submission =
                new PartnerSubmission();

        submission.setBusiness(business);
        submission.setSubmittedBy(submitter);
        submission.setSubmissionType("BUSINESS_PROFILE");

        // Tạo snapshot thay vì serialize toàn bộ Entity.
        submission.setSubmissionData(
                createBusinessSnapshot(business)
        );

        submission.setStatus("PENDING");

        PartnerSubmission saved =
                submissionRepository.save(submission);

        // Chuyển trạng thái Business sang chờ Admin duyệt.
        business.setVerificationStatus("PENDING_REVIEW");
        business.setPublicationStatus("DRAFT");

        businessRepository.save(business);

        // Ghi lịch sử thao tác.
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

    /**
     * Xem lịch sử gửi duyệt của Business.
     */
    @Transactional(readOnly = true)
    public List<PartnerSubmission> history(
            Long businessId,
            String email
    ) {

        accessService.requireApprovedPartner(email);

        // Kiểm tra quyền sở hữu.
        businessRepository
                .findByIdAndPartner_User_EmailAndDeletedAtIsNull(
                        businessId,
                        email
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy cơ sở kinh doanh "
                                        + "hoặc bạn không có quyền quản lý."
                        )
                );

        return submissionRepository
                .findByBusiness_IdOrderBySubmittedAtDesc(
                        businessId
                );
    }

    /**
     * Tạo JSON snapshot thông tin Business.
     *
     * Chỉ lưu những trường cần thiết để Admin kiểm duyệt.
     * Không serialize các quan hệ JPA.
     */
    private String createBusinessSnapshot(
            PartnerBusiness business
    ) {

        Map<String, Object> snapshot =
                new LinkedHashMap<>();

        snapshot.put("businessId", business.getId());

        snapshot.put("name", business.getName());

        snapshot.put("slug", business.getSlug());

        snapshot.put("businessType", business.getBusinessType());

        snapshot.put(
                "shortDescription",
                business.getShortDescription()
        );

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

        snapshot.put(
                "verificationStatus",
                business.getVerificationStatus()
        );

        snapshot.put(
                "publicationStatus",
                business.getPublicationStatus()
        );

        snapshot.put(
                "operatingStatus",
                business.getOperatingStatus()
        );

        snapshot.put("createdAt", business.getCreatedAt());

        try {

            return jsonMapper.writeValueAsString(snapshot);

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Không thể tạo JSON snapshot gửi Admin duyệt.",
                    exception
            );
        }
    }
}