package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PartnerBusinessRequest;
import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.PartnerProfile;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PartnerBusinessService {

    private static final Set<String> BUSINESS_TYPES = Set.of(
            "HOTEL", "HOMESTAY", "RESORT", "HOSTEL", "APARTMENT",
            "CAFE", "COFFEE_SHOP", "RESTAURANT", "EATERY",
            "ATTRACTION", "TOURIST_SITE", "SPA", "SHOP", "OTHER"
    );

    private final PartnerBusinessRepository businessRepository;
    private final PartnerAccessService partnerAccessService;
    private final PartnerAuditService auditService;

    @Transactional
    public PartnerBusiness create(PartnerBusinessRequest request, String email) {
        validate(request);
        PartnerProfile partner = partnerAccessService.requireApprovedPartner(email);

        PartnerBusiness business = new PartnerBusiness();
        business.setPartner(partner);
        apply(business, request);
        business.setSlug(createUniqueSlug(request.getName()));
        business.setVerificationStatus("DRAFT");
        business.setPublicationStatus("DRAFT");
        business.setOperatingStatus("OPEN");

        PartnerBusiness saved = businessRepository.save(business);
        auditService.log(email, saved.getId(), "CREATE", "BUSINESS", saved.getId(), null, saved.getName());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PartnerBusiness> getMine(String email) {
        PartnerProfile partner = partnerAccessService.requireApprovedPartner(email);
        return businessRepository.findByPartner_IdAndDeletedAtIsNullOrderByCreatedAtDesc(partner.getId());
    }

    @Transactional(readOnly = true)
    public PartnerBusiness getMineById(Long businessId, String email) {
        partnerAccessService.requireApprovedPartner(email);
        return getOwnedBusiness(businessId, email);
    }

    @Transactional
    public PartnerBusiness update(Long businessId, PartnerBusinessRequest request, String email) {
        validate(request);
        partnerAccessService.requireApprovedPartner(email);

        PartnerBusiness business = getOwnedBusiness(businessId, email);
        ensureNotPendingReview(business);
        String oldName = business.getName();

        if (!oldName.equals(request.getName().trim())) {
            business.setSlug(createUniqueSlug(request.getName()));
        }

        apply(business, request);
        resetToDraft(business);

        PartnerBusiness saved = businessRepository.save(business);
        auditService.log(email, businessId, "UPDATE", "BUSINESS", businessId, oldName, saved.getName());
        return saved;
    }

    @Transactional
    public void softDelete(Long businessId, String email) {
        partnerAccessService.requireApprovedPartner(email);

        PartnerBusiness business = getOwnedBusiness(businessId, email);
        ensureNotPendingReview(business);
        business.setDeletedAt(LocalDateTime.now());
        business.setPublicationStatus("HIDDEN");
        businessRepository.save(business);

        auditService.log(email, businessId, "SOFT_DELETE", "BUSINESS", businessId, business.getName(), null);
    }

    private PartnerBusiness getOwnedBusiness(Long businessId, String email) {
        return businessRepository
                .findByIdAndPartner_User_EmailAndDeletedAtIsNull(businessId, email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy cơ sở kinh doanh hoặc bạn không có quyền quản lý."
                ));
    }

    private void ensureNotPendingReview(PartnerBusiness business) {
        if ("PENDING_REVIEW".equalsIgnoreCase(business.getVerificationStatus())) {
            throw new IllegalStateException(
                    "Địa điểm đang chờ Admin xét duyệt. Không thể chỉnh sửa hoặc ẩn hồ sơ lúc này."
            );
        }
    }

    private void resetToDraft(PartnerBusiness business) {
        business.setVerificationStatus("DRAFT");
        business.setPublicationStatus("DRAFT");
        business.setVerifiedBy(null);
        business.setVerifiedAt(null);
        business.setRejectionReason(null);
    }

    private void validate(PartnerBusinessRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu cơ sở kinh doanh không hợp lệ.");
        }
        requireText(request.getName(), "Tên cơ sở kinh doanh không được để trống.");
        requireText(request.getAddress(), "Địa chỉ không được để trống.");
        requireText(request.getCity(), "Thành phố không được để trống.");

        String type = request.getBusinessType() == null
                ? ""
                : request.getBusinessType().trim().toUpperCase(Locale.ROOT);
        if (!BUSINESS_TYPES.contains(type)) {
            throw new IllegalArgumentException("Loại hình kinh doanh không hợp lệ.");
        }

        if ((request.getLatitude() == null) != (request.getLongitude() == null)) {
            throw new IllegalArgumentException("Latitude và longitude phải được nhập cùng nhau.");
        }
        if (request.getLatitude() != null
                && (request.getLatitude().compareTo(BigDecimal.valueOf(-90)) < 0
                || request.getLatitude().compareTo(BigDecimal.valueOf(90)) > 0)) {
            throw new IllegalArgumentException("Latitude không hợp lệ.");
        }
        if (request.getLongitude() != null
                && (request.getLongitude().compareTo(BigDecimal.valueOf(-180)) < 0
                || request.getLongitude().compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new IllegalArgumentException("Longitude không hợp lệ.");
        }
        if (request.getPriceLevel() != null
                && (request.getPriceLevel() < 1 || request.getPriceLevel() > 4)) {
            throw new IllegalArgumentException("priceLevel phải nằm trong khoảng từ 1 đến 4.");
        }
    }

    private void apply(PartnerBusiness business, PartnerBusinessRequest request) {
        business.setName(request.getName().trim());
        business.setBusinessType(request.getBusinessType().trim().toUpperCase(Locale.ROOT));
        business.setShortDescription(trimToNull(request.getShortDescription()));
        business.setDescription(trimToNull(request.getDescription()));
        business.setAddress(request.getAddress().trim());
        business.setWard(trimToNull(request.getWard()));
        business.setDistrict(trimToNull(request.getDistrict()));
        business.setCity(request.getCity().trim());
        business.setProvince(trimToNull(request.getProvince()));
        business.setCountryCode(request.getCountryCode() == null || request.getCountryCode().isBlank()
                ? "VN" : request.getCountryCode().trim().toUpperCase(Locale.ROOT));
        business.setLatitude(request.getLatitude());
        business.setLongitude(request.getLongitude());
        business.setPhoneNumber(trimToNull(request.getPhoneNumber()));
        business.setEmail(trimToNull(request.getEmail()));
        business.setWebsiteUrl(trimToNull(request.getWebsiteUrl()));
        business.setFacebookUrl(trimToNull(request.getFacebookUrl()));
        business.setInstagramUrl(trimToNull(request.getInstagramUrl()));
        business.setPriceLevel(request.getPriceLevel());
        business.setCurrency(request.getCurrency() == null || request.getCurrency().isBlank()
                ? "VND" : request.getCurrency().trim().toUpperCase(Locale.ROOT));
    }

    private String createUniqueSlug(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");

        if (base.isBlank()) base = "business";
        if (base.length() > 220) base = base.substring(0, 220).replaceAll("-$", "");

        String candidate = base;
        int suffixNumber = 2;
        while (businessRepository.existsBySlug(candidate)) {
            String suffix = "-" + suffixNumber++;
            int maxBaseLength = 255 - suffix.length();
            candidate = base.substring(0, Math.min(base.length(), maxBaseLength)).replaceAll("-$", "") + suffix;
        }
        return candidate;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    }
}
