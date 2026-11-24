package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PartnerBusiness;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/** Repository used by the explicit Admin-confirmation Place workflow. */
@Repository
public interface PartnerBusinessLinkRepository extends JpaRepository<PartnerBusiness, Long> {

    @Query("""
            SELECT b FROM PartnerBusiness b
            WHERE b.verificationStatus IN ('APPROVED', 'VERIFIED')
              AND b.publicationStatus = 'PUBLISHED'
              AND b.operatingStatus = 'OPEN'
              AND b.deletedAt IS NULL
              AND b.latitude IS NOT NULL
              AND b.longitude IS NOT NULL
              AND b.latitude BETWEEN :minLatitude AND :maxLatitude
              AND b.longitude BETWEEN :minLongitude AND :maxLongitude
            """)
    List<PartnerBusiness> findPublishedBusinessesNear(
            @Param("minLatitude") BigDecimal minLatitude,
            @Param("maxLatitude") BigDecimal maxLatitude,
            @Param("minLongitude") BigDecimal minLongitude,
            @Param("maxLongitude") BigDecimal maxLongitude
    );
}
