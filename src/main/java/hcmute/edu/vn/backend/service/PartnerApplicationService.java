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
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PartnerApplicationService {

    private final PartnerApplicationRepository applicationRepository;

    private final PartnerProfileRepository partnerProfileRepository;

    private final UserRepository userRepository;

    private final PartnerAuditService auditService;

    /**
     * Mã số thuế: 10 số (doanh nghiệp/hộ kinh doanh), 10 số + "-" + 3 số (chi nhánh, đơn vị phụ thuộc)
     * hoặc 12 số (mã định danh cá nhân dùng làm mã số thuế cho cá nhân/hộ kinh doanh).
     * Chỉ kiểm tra ĐỊNH DẠNG; mã có thật hay không do Admin tra cứu khi duyệt.
     */
    private static final Pattern TAX_CODE_PATTERN =
            Pattern.compile("^(\\d{10}(-\\d{3})?|\\d{12})$");

    /** Số điện thoại Việt Nam: 0xxxxxxxxx hoặc +84xxxxxxxxx (9–10 số sau đầu số). */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^(0|\\+84)\\d{9,10}$");

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final List<String> ACTIVE_STATUSES =
            List.of("PENDING", "APPROVED");

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

        // Một mã số thuế chỉ gắn với một tài khoản đối tác.
        if (applicationRepository
                .existsByTaxCodeAndVerificationStatusInAndUser_IdNot(
                        normalizeTaxCode(request.getTaxCode()),
                        ACTIVE_STATUSES,
                        user.getId()
                )) {

            throw new IllegalStateException(
                    "Mã số thuế này đã được đăng ký bởi một tài khoản khác."
                            + " Nếu đây là đơn vị của bạn, vui lòng liên hệ quản trị viên."
            );
        }

        PartnerApplication application =
                new PartnerApplication();

        application.setUser(user);

        application.setDisplayName(
                request.getDisplayName().trim()
        );

        application.setLegalName(trim(request.getLegalName()));

        application.setTaxCode(normalizeTaxCode(request.getTaxCode()));

        application.setContactPerson(
                trim(request.getContactPerson())
        );

        application.setContactEmail(
                trim(request.getContactEmail())
        );

        application.setContactPhone(
                normalizePhone(request.getContactPhone())
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
     * Đối tác phải là hộ kinh doanh / doanh nghiệp có mã số thuế, nên các thông tin pháp lý là bắt buộc.
     */
    private void validateRequest(
            PartnerApplicationRequest request
    ) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "Thiếu thông tin đăng ký."
            );
        }

        requireText(request.getDisplayName(), "Tên đơn vị / tên hiển thị", 255);
        requireText(request.getLegalName(), "Tên pháp lý", 255);
        requireText(request.getTaxCode(), "Mã số thuế", 50);
        requireText(request.getContactPerson(), "Người liên hệ", 150);
        requireText(request.getContactPhone(), "Số điện thoại", 20);
        requireText(request.getBusinessAddress(), "Địa chỉ đơn vị", 500);

        if (!TAX_CODE_PATTERN.matcher(normalizeTaxCode(request.getTaxCode())).matches()) {
            throw new IllegalArgumentException(
                    "Mã số thuế không đúng định dạng"
                            + " (10 số, 10 số kèm -3 số của chi nhánh, hoặc 12 số)."
            );
        }

        if (!PHONE_PATTERN.matcher(normalizePhone(request.getContactPhone())).matches()) {
            throw new IllegalArgumentException(
                    "Số điện thoại không hợp lệ."
            );
        }

        String email = trim(request.getContactEmail());
        if (email != null
                && (email.length() > 255 || !EMAIL_PATTERN.matcher(email).matches())) {
            throw new IllegalArgumentException(
                    "Email liên hệ không hợp lệ."
            );
        }

        String description = trim(request.getDescription());
        if (description != null && description.length() > 2000) {
            throw new IllegalArgumentException(
                    "Mô tả không được vượt quá 2000 ký tự."
            );
        }
    }

    private void requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    label + " không được để trống."
            );
        }
        if (value.trim().length() > maxLength) {
            throw new IllegalArgumentException(
                    label + " không được vượt quá " + maxLength + " ký tự."
            );
        }
    }

    /** Bỏ khoảng trắng và dấu chấm người dùng hay gõ kèm: "0312 345 678" -> "0312345678". */
    private String normalizeTaxCode(String value) {
        return value == null ? null : value.replaceAll("[\\s.]", "");
    }

    /** "0901 234 567", "0901.234.567" -> "0901234567". */
    private String normalizePhone(String value) {
        return value == null ? null : value.replaceAll("[\\s.()-]", "");
    }

    private String trim(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}