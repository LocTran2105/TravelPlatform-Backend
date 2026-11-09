package hcmute.edu.vn.backend.dto;

import hcmute.edu.vn.backend.entity.PartnerSubmission;

import java.time.LocalDateTime;

public record PartnerSubmissionAdminResponse(
        Long id,
        Long businessId,
        String businessName,
        String businessType,
        String city,
        String submittedByEmail,
        String submissionType,
        String submissionData,
        String status,
        String reviewNote,
        String reviewedByEmail,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt
) {

    public static PartnerSubmissionAdminResponse from(
            PartnerSubmission submission
    ) {

        return new PartnerSubmissionAdminResponse(
                submission.getId(),
                submission.getBusiness().getId(),
                submission.getBusiness().getName(),
                submission.getBusiness().getBusinessType(),
                submission.getBusiness().getCity(),
                submission.getSubmittedBy().getEmail(),
                submission.getSubmissionType(),
                submission.getSubmissionData(),
                submission.getStatus(),
                submission.getReviewNote(),
                submission.getReviewedBy() == null
                        ? null
                        : submission.getReviewedBy().getEmail(),
                submission.getSubmittedAt(),
                submission.getReviewedAt()
        );
    }
}