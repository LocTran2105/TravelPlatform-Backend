package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.PartnerAuditLog;
import hcmute.edu.vn.backend.entity.PartnerProfile;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.PartnerAuditLogRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.PartnerProfileRepository;
import hcmute.edu.vn.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PartnerAuditService {

    private final PartnerAuditLogRepository auditRepository;

    private final UserRepository userRepository;

    private final PartnerProfileRepository partnerProfileRepository;

    private final PartnerBusinessRepository businessRepository;

    // Spring Boot 4 tự inject JsonMapper (Jackson 3).
    private final JsonMapper jsonMapper;

    /**
     * Ghi lại lịch sử thao tác.
     *
     * @param email       Email người thực hiện
     * @param businessId  ID cơ sở kinh doanh
     * @param action      Hành động CREATE, UPDATE, DELETE...
     * @param entityType  Loại dữ liệu BUSINESS, MENU_ITEM...
     * @param entityId    ID đối tượng bị tác động
     * @param before      Dữ liệu trước khi thay đổi
     * @param after       Dữ liệu sau khi thay đổi
     */
    @Transactional
    public void log(
            String email,
            Long businessId,
            String action,
            String entityType,
            Long entityId,
            Object before,
            Object after
    ) {

        User actor = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy tài khoản thao tác."
                        )
                );

        PartnerAuditLog auditLog = new PartnerAuditLog();

        // Người thực hiện thao tác.
        auditLog.setActor(actor);

        // Tìm hồ sơ đối tác tương ứng.
        partnerProfileRepository
                .findByUser_Id(actor.getId())
                .ifPresent(auditLog::setPartner);

        // Gắn Business nếu thao tác liên quan đến Business.
        if (businessId != null) {

            businessRepository.findById(businessId)
                    .ifPresent(auditLog::setBusiness);
        }

        auditLog.setAction(action);
        auditLog.setEntityType(entityType);
        auditLog.setEntityId(entityId);

        // Chuyển dữ liệu trước và sau thành JSON.
        auditLog.setBeforeData(toJson(before));
        auditLog.setAfterData(toJson(after));

        auditRepository.save(auditLog);
    }

    /**
     * Lấy tối đa 100 audit log của một Business.
     *
     * Chỉ Partner sở hữu Business mới được xem.
     */
    @Transactional(readOnly = true)
    public List<PartnerAuditLog> getBusinessLogs(
            Long businessId,
            String email
    ) {

        PartnerProfile partner = partnerProfileRepository
                .findByUser_Email(email)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy hồ sơ đối tác."
                        )
                );

        businessRepository
                .findByIdAndPartner_IdAndDeletedAtIsNull(
                        businessId,
                        partner.getId()
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy cơ sở kinh doanh "
                                        + "hoặc bạn không có quyền quản lý."
                        )
                );

        return auditRepository
                .findTop100ByBusiness_IdOrderByCreatedAtDesc(
                        businessId
                );
    }

    /**
     * Chuyển dữ liệu thành JSON String.
     */
    private String toJson(Object value) {

        if (value == null) {
            return null;
        }

        try {

            return jsonMapper.writeValueAsString(value);

        } catch (JacksonException exception) {

            throw new IllegalStateException(
                    "Không thể chuyển dữ liệu audit log thành JSON.",
                    exception
            );
        }
    }
}