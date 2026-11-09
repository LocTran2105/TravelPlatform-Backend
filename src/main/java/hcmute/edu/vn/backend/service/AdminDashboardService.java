package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.AdminDashboardResponse;
import hcmute.edu.vn.backend.repository.PartnerApplicationRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.PartnerProfileRepository;
import hcmute.edu.vn.backend.repository.PartnerSubmissionRepository;
import hcmute.edu.vn.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final UserRepository userRepository;

    private final PartnerProfileRepository partnerProfileRepository;

    private final PartnerApplicationRepository applicationRepository;

    private final PartnerBusinessRepository businessRepository;

    private final PartnerSubmissionRepository submissionRepository;

    @Transactional(readOnly = true)
    public AdminDashboardResponse getDashboard() {

        return new AdminDashboardResponse(
                userRepository.count(),

                userRepository.countByStatus("ACTIVE"),

                userRepository.countByStatus("LOCKED"),

                userRepository.countByRole("ROLE_TRAVELLER"),

                userRepository.countByRole("ROLE_PARTNER"),

                partnerProfileRepository.count(),

                applicationRepository.countByVerificationStatus(
                        "PENDING"
                ),

                partnerProfileRepository.countByVerificationStatus(
                        "APPROVED"
                ),

                businessRepository.countByDeletedAtIsNull(),

                businessRepository.countByVerificationStatusAndDeletedAtIsNull(
                        "PENDING_REVIEW"
                ),

                submissionRepository.countByStatus("PENDING"),

                businessRepository.countByPublicationStatusAndDeletedAtIsNull(
                        "PUBLISHED"
                )
        );
    }
}