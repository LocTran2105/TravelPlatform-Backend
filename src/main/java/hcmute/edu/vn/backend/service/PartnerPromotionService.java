package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PromotionRequest;
import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.PartnerPromotion;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.PartnerPromotionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PartnerPromotionService {

    private final PartnerBusinessRepository businessRepository;
    private final PartnerPromotionRepository promotionRepository;
    private final PartnerAccessService accessService;
    private final PartnerAuditService auditService;

    @Transactional(readOnly = true)
    public List<PartnerPromotion> list(Long businessId, String email) {
        requireOwnedBusiness(businessId, email);
        return promotionRepository.findByBusiness_IdOrderByStartsAtDesc(businessId);
    }

    @Transactional
    public PartnerPromotion create(
            Long businessId,
            PromotionRequest request,
            String email
    ) {
        PartnerBusiness business = requireOwnedBusiness(businessId, email);
        validateRequest(request);

        PartnerPromotion promotion = new PartnerPromotion();
        promotion.setBusiness(business);
        apply(promotion, request);
        promotion.setStatus("DRAFT");

        PartnerPromotion saved = promotionRepository.save(promotion);

        auditService.log(
                email,
                businessId,
                "CREATE",
                "PROMOTION",
                saved.getId(),
                null,
                saved.getTitle()
        );

        return saved;
    }

    @Transactional
    public PartnerPromotion update(
            Long businessId,
            Long promotionId,
            PromotionRequest request,
            String email
    ) {
        requireOwnedBusiness(businessId, email);
        validateRequest(request);

        PartnerPromotion promotion = promotionRepository
                .findByIdAndBusiness_Id(promotionId, businessId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy khuyến mãi."
                ));

        String oldTitle = promotion.getTitle();
        apply(promotion, request);
        promotion.setStatus("DRAFT");

        PartnerPromotion saved = promotionRepository.save(promotion);

        auditService.log(
                email,
                businessId,
                "UPDATE",
                "PROMOTION",
                promotionId,
                oldTitle,
                saved.getTitle()
        );

        return saved;
    }

    @Transactional
    public void deactivate(Long businessId, Long promotionId, String email) {
        requireOwnedBusiness(businessId, email);

        PartnerPromotion promotion = promotionRepository
                .findByIdAndBusiness_Id(promotionId, businessId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy khuyến mãi."
                ));

        promotion.setStatus("INACTIVE");
        promotionRepository.save(promotion);

        auditService.log(
                email,
                businessId,
                "DEACTIVATE",
                "PROMOTION",
                promotionId,
                promotion.getTitle(),
                null
        );
    }

    private PartnerBusiness requireOwnedBusiness(Long businessId, String email) {
        accessService.requireApprovedPartner(email);

        return businessRepository
                .findByIdAndPartner_User_EmailAndDeletedAtIsNull(businessId, email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy cơ sở kinh doanh hoặc bạn không có quyền quản lý."
                ));
    }

    private void validateRequest(PromotionRequest request) {
        if (request == null
                || request.getTitle() == null
                || request.getTitle().isBlank()
                || request.getPromotionType() == null
                || request.getPromotionType().isBlank()
                || request.getStartsAt() == null) {
            throw new IllegalArgumentException(
                    "Thiếu thông tin khuyến mãi bắt buộc."
            );
        }

        if (request.getEndsAt() != null
                && !request.getEndsAt().isAfter(request.getStartsAt())) {
            throw new IllegalArgumentException(
                    "Thời điểm kết thúc phải sau thời điểm bắt đầu."
            );
        }

        if (request.getDiscountValue() != null
                && request.getDiscountValue().signum() < 0) {
            throw new IllegalArgumentException(
                    "Giá trị giảm giá không hợp lệ."
            );
        }
    }

    private void apply(
            PartnerPromotion promotion,
            PromotionRequest request
    ) {
        promotion.setTitle(request.getTitle().trim());
        promotion.setDescription(request.getDescription());
        promotion.setPromotionType(
                request.getPromotionType().trim().toUpperCase(Locale.ROOT)
        );
        promotion.setDiscountValue(request.getDiscountValue());
        promotion.setStartsAt(request.getStartsAt());
        promotion.setEndsAt(request.getEndsAt());
        promotion.setTerms(request.getTerms());
    }
}
