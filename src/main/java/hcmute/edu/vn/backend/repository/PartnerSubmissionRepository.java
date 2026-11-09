package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PartnerSubmission;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PartnerSubmissionRepository
        extends JpaRepository<PartnerSubmission, Long> {

    List<PartnerSubmission>
    findByBusiness_IdOrderBySubmittedAtDesc(Long businessId);

    List<PartnerSubmission>
    findByStatusOrderBySubmittedAtAsc(String status);

    boolean existsByBusiness_IdAndStatus(
            Long businessId,
            String status
    );

    long countByBusiness_Partner_IdAndStatus(
            Long partnerId,
            String status
    );

    long countByStatus(String status);

    /**
     * Khóa yêu cầu duyệt và tải Business + người gửi.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT submission
        FROM PartnerSubmission submission
        JOIN FETCH submission.business
        JOIN FETCH submission.submittedBy
        WHERE submission.id = :submissionId
        """)
    Optional<PartnerSubmission> findByIdForReview(
            @Param("submissionId") Long submissionId
    );
}