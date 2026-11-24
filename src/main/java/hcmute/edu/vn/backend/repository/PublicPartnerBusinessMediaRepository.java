package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PartnerBusinessMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PublicPartnerBusinessMediaRepository extends JpaRepository<PartnerBusinessMedia, Long> {

    @Query("""
            SELECT m FROM PartnerBusinessMedia m
            JOIN m.business b
            WHERE b.id IN :businessIds
              AND b.verificationStatus IN ('APPROVED', 'VERIFIED')
              AND b.publicationStatus = 'PUBLISHED'
              AND b.operatingStatus = 'OPEN'
              AND b.deletedAt IS NULL
              AND UPPER(m.mediaType) = 'IMAGE'
              AND UPPER(m.moderationStatus) = 'APPROVED'
            ORDER BY CASE WHEN m.cover = TRUE THEN 0 ELSE 1 END,
                     COALESCE(m.sortOrder, 2147483647),
                     m.id ASC
            """)
    List<PartnerBusinessMedia> findPublicImagesForBusinesses(
            @Param("businessIds") Collection<Long> businessIds);
}
