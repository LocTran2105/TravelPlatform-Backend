package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PartnerApplication;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PartnerApplicationRepository
        extends JpaRepository<PartnerApplication, Long> {

    List<PartnerApplication> findByUser_IdOrderByCreatedAtDesc(
            Long userId
    );

    Optional<PartnerApplication> findTopByUser_IdOrderByCreatedAtDesc(
            Long userId
    );

    boolean existsByUser_IdAndVerificationStatus(
            Long userId,
            String verificationStatus
    );

    List<PartnerApplication>
    findByVerificationStatusOrderByCreatedAtAsc(
            String verificationStatus
    );

    Page<PartnerApplication>
    findByVerificationStatusOrderByCreatedAtAsc(
            String verificationStatus,
            Pageable pageable
    );

    long countByVerificationStatus(String verificationStatus);

    /**
     * Khóa đơn đăng ký trong lúc Admin duyệt.
     * JOIN FETCH để lấy User gửi đơn ngay trong transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT application
            FROM PartnerApplication application
            JOIN FETCH application.user
            WHERE application.id = :applicationId
            """)
    Optional<PartnerApplication> findByIdForReview(
            @Param("applicationId") Long applicationId
    );
    Page<PartnerApplication> findByVerificationStatus(
            String verificationStatus,
            Pageable pageable
    );
}