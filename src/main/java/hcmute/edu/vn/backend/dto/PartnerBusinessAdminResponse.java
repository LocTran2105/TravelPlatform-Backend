package hcmute.edu.vn.backend.dto;

import hcmute.edu.vn.backend.entity.PartnerBusiness;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
        LocalDateTime verifiedAt
) {

    public static PartnerBusinessAdminResponse from(
            PartnerBusiness business
    ) {

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
                business.getVerifiedAt()
        );
    }
}