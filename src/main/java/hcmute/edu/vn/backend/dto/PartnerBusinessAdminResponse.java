package hcmute.edu.vn.backend.dto;

import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.Place;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Business cho trang quản trị. placeId / placeProvider / providerPlaceId là Place đang liên kết
 * (OSM đã xác nhận hoặc PARTNER); null khi Business chưa được duyệt vị trí.
 */
public record PartnerBusinessAdminResponse(
        Long id,
        Long partnerId,
        String partnerName,
        String partnerEmail,
        String name,
        String businessType,
        String address,
        String ward,
        String district,
        String city,
        String province,
        BigDecimal latitude,
        BigDecimal longitude,
        String phoneNumber,
        String email,
        String verificationStatus,
        String publicationStatus,
        String operatingStatus,
        LocalDateTime createdAt,
        LocalDateTime verifiedAt,
        Long placeId,
        String placeProvider,
        String providerPlaceId
) {

    public static PartnerBusinessAdminResponse from(PartnerBusiness business) {
        return from(business, null);
    }

    public static PartnerBusinessAdminResponse from(PartnerBusiness business, Place linkedPlace) {
        return new PartnerBusinessAdminResponse(
                business.getId(),
                business.getPartner().getId(),
                business.getPartner().getDisplayName(),
                business.getPartner().getUser().getEmail(),
                business.getName(),
                business.getBusinessType(),
                business.getAddress(),
                business.getWard(),
                business.getDistrict(),
                business.getCity(),
                business.getProvince(),
                business.getLatitude(),
                business.getLongitude(),
                business.getPhoneNumber(),
                business.getEmail(),
                business.getVerificationStatus(),
                business.getPublicationStatus(),
                business.getOperatingStatus(),
                business.getCreatedAt(),
                business.getVerifiedAt(),
                linkedPlace == null ? null : linkedPlace.getId(),
                linkedPlace == null ? null : linkedPlace.getProvider(),
                linkedPlace == null ? null : linkedPlace.getProviderPlaceId()
        );
    }
}
