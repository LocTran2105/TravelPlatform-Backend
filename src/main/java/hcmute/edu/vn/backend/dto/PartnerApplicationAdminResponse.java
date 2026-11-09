package hcmute.edu.vn.backend.dto;

import hcmute.edu.vn.backend.entity.PartnerApplication;

import java.time.LocalDateTime;

public record PartnerApplicationAdminResponse(
        Long id,
        Long userId,
        String userEmail,
        String userFullName,
        String displayName,
        String legalName,
        String taxCode,
        String contactPerson,
        String contactEmail,
        String contactPhone,
        String businessAddress,
        String description,
        String verificationStatus,
        String adminNote,
        String reviewedByEmail,
        LocalDateTime reviewedAt,
        LocalDateTime createdAt
) {

    public static PartnerApplicationAdminResponse from(
            PartnerApplication application
    ) {

        return new PartnerApplicationAdminResponse(
                application.getId(),
                application.getUser().getId(),
                application.getUser().getEmail(),
                application.getUser().getFullName(),
                application.getDisplayName(),
                application.getLegalName(),
                application.getTaxCode(),
                application.getContactPerson(),
                application.getContactEmail(),
                application.getContactPhone(),
                application.getBusinessAddress(),
                application.getDescription(),
                application.getVerificationStatus(),
                application.getAdminNote(),
                application.getReviewedBy() == null
                        ? null
                        : application.getReviewedBy().getEmail(),
                application.getReviewedAt(),
                application.getCreatedAt()
        );
    }
}