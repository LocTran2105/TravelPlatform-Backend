package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PartnerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PartnerProfileRepository
        extends JpaRepository<PartnerProfile, Long> {

    Optional<PartnerProfile> findByUser_Id(Long userId);

    Optional<PartnerProfile> findByUser_Email(String email);

    boolean existsByUser_Id(Long userId);

    boolean existsByUser_Email(String email);

    long countByVerificationStatus(String verificationStatus);

    List<PartnerProfile>
    findByVerificationStatusOrderByCreatedAtDesc(
            String verificationStatus
    );
}