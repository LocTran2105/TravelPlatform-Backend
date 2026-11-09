package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PartnerDashboardResponse;
import hcmute.edu.vn.backend.entity.PartnerProfile;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.PartnerMenuItemRepository;
import hcmute.edu.vn.backend.repository.PartnerPromotionRepository;
import hcmute.edu.vn.backend.repository.PartnerSubmissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PartnerDashboardService {

    private final PartnerAccessService accessService;
    private final PartnerBusinessRepository businessRepository;
    private final PartnerMenuItemRepository menuItemRepository;
    private final PartnerPromotionRepository promotionRepository;
    private final PartnerSubmissionRepository submissionRepository;

    @Transactional(readOnly = true)
    public PartnerDashboardResponse getDashboard(String email) {
        PartnerProfile partner = accessService.requireApprovedPartner(email);
        Long partnerId = partner.getId();

        return new PartnerDashboardResponse(
                businessRepository.countByPartner_IdAndDeletedAtIsNull(partnerId),
                businessRepository.countByPartner_IdAndVerificationStatusAndDeletedAtIsNull(
                        partnerId,
                        "PENDING_REVIEW"
                ),
                businessRepository.countByPartner_IdAndPublicationStatusAndDeletedAtIsNull(
                        partnerId,
                        "PUBLISHED"
                ),
                menuItemRepository.countByCategory_Business_Partner_Id(partnerId),
                promotionRepository.countByBusiness_Partner_IdAndStatus(
                        partnerId,
                        "ACTIVE"
                ),
                submissionRepository.countByBusiness_Partner_IdAndStatus(
                        partnerId,
                        "PENDING"
                )
        );
    }
}
