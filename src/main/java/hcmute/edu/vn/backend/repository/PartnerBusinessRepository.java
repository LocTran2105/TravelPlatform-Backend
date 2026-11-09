package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PartnerBusiness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PartnerBusinessRepository
        extends JpaRepository<PartnerBusiness, Long> {

    List<PartnerBusiness>
    findByPartner_IdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long partnerId
    );

    List<PartnerBusiness>
    findByPartner_User_EmailAndDeletedAtIsNullOrderByCreatedAtDesc(
            String email
    );

    Optional<PartnerBusiness>
    findByIdAndPartner_User_EmailAndDeletedAtIsNull(
            Long businessId,
            String email
    );

    Optional<PartnerBusiness>
    findByIdAndPartner_IdAndDeletedAtIsNull(
            Long businessId,
            Long partnerId
    );

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    long countByPartner_IdAndDeletedAtIsNull(Long partnerId);

    long countByPartner_IdAndVerificationStatusAndDeletedAtIsNull(
            Long partnerId,
            String verificationStatus
    );

    long countByPartner_IdAndPublicationStatusAndDeletedAtIsNull(
            Long partnerId,
            String publicationStatus
    );

    // Thống kê cho Admin.
    long countByDeletedAtIsNull();

    long countByVerificationStatusAndDeletedAtIsNull(
            String verificationStatus
    );

    long countByPublicationStatusAndDeletedAtIsNull(
            String publicationStatus
    );

    /**
     * Admin tìm kiếm Business theo trạng thái và từ khóa.
     * Các tham số lọc có thể null.
     */
    @Query("""
            SELECT business
            FROM PartnerBusiness business
            JOIN FETCH business.partner partner
            JOIN FETCH partner.user
            WHERE business.deletedAt IS NULL
              AND (
                    :verificationStatus IS NULL
                    OR business.verificationStatus = :verificationStatus
              )
              AND (
                    :publicationStatus IS NULL
                    OR business.publicationStatus = :publicationStatus
              )
              AND (
                    :businessType IS NULL
                    OR business.businessType = :businessType
              )
              AND (
                    :keyword IS NULL
                    OR LOWER(business.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(business.address) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(business.city) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
            """)
    Page<PartnerBusiness> searchForAdmin(
            @Param("verificationStatus") String verificationStatus,
            @Param("publicationStatus") String publicationStatus,
            @Param("businessType") String businessType,
            @Param("keyword") String keyword,
            Pageable pageable
    );
}