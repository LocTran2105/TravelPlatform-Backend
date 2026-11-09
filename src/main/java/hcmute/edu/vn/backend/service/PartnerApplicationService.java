package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PartnerApplicationRequest;
import hcmute.edu.vn.backend.entity.PartnerApplication;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.PartnerApplicationRepository;
import hcmute.edu.vn.backend.repository.PartnerProfileRepository;
import hcmute.edu.vn.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PartnerApplicationService {

    private final PartnerApplicationRepository applicationRepository;

    private final PartnerProfileRepository partnerProfileRepository;

    private final UserRepository userRepository;

    private final PartnerAuditService auditService;

    /**
     * Người dùng gửi đơn đăng ký Partner.
     */
    @Transactional
    public PartnerApplication apply(
            PartnerApplicationRequest request,
            String email
    ) {

        validateRequest(request);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy tài khoản."
                        )
                );

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalStateException(
                    "Tài khoản hiện không hoạt động."
            );
        }

        // Chỉ Traveller được phép gửi đơn đăng ký.
        if (!"ROLE_TRAVELLER".equalsIgnoreCase(user.getRole())) {
            throw new IllegalStateException(
                    "Chỉ tài khoản Traveller mới được đăng ký đối tác."
            );
        }

        if (partnerProfileRepository.existsByUser_Id(user.getId())) {
            throw new IllegalStateException(
                    "Tài khoản đã có hồ sơ đối tác."
            );
        }

        if (applicationRepository
                .existsByUser_IdAndVerificationStatus(
                        user.getId(),
                        "PENDING"
                )) {

            throw new IllegalStateException(
                    "Bạn đã có đơn đăng ký đối tác đang chờ duyệt."
            );
        }

        PartnerApplication application =
                new PartnerApplication();

        application.setUser(user);

        application.setDisplayName(
                request.getDisplayName().trim()
        );

        application.setLegalName(trim(request.getLegalName()));

        application.setTaxCode(trim(request.getTaxCode()));

        application.setContactPerson(
                trim(request.getContactPerson())
        );

        application.setContactEmail(
                trim(request.getContactEmail())
        );

        application.setContactPhone(
                trim(request.getContactPhone())
        );

        application.setBusinessAddress(
                trim(request.getBusinessAddress())
        );

        application.setDescription(
                trim(request.getDescription())
        );

        application.setVerificationStatus("PENDING");

        PartnerApplication saved =
                applicationRepository.save(application);

        // Ghi nhận người dùng gửi đơn.
        auditService.log(
                email,
                null,
                "SUBMIT_PARTNER_APPLICATION",
                "PARTNER_APPLICATION",
                saved.getId(),
                null,
                "PENDING"
        );

        return saved;
    }

    /**
     * Người dùng xem lịch sử đơn đăng ký của mình.
     */
    @Transactional(readOnly = true)
    public List<PartnerApplication> getMyApplications(
            String email
    ) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy tài khoản."
                        )
                );

        return applicationRepository
                .findByUser_IdOrderByCreatedAtDesc(user.getId());
    }

    /**
     * Kiểm tra dữ liệu đăng ký.
     */
    private void validateRequest(
            PartnerApplicationRequest request
    ) {

        if (request == null
                || request.getDisplayName() == null
                || request.getDisplayName().isBlank()) {

            throw new IllegalArgumentException(
                    "Tên đối tác không được để trống."
            );
        }

        if (request.getDisplayName().trim().length() > 255) {
            throw new IllegalArgumentException(
                    "Tên đối tác không được vượt quá 255 ký tự."
            );
        }
    }

    private String trim(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}