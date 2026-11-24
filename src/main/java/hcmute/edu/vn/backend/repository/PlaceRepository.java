package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlaceRepository extends JpaRepository<Place, Long> {

    List<Place> findByDestinationIgnoreCase(String destination);

    List<Place> findByDestinationIgnoreCaseAndPlaceTypeIgnoreCase(
            String destination,
            String placeType
    );

    List<Place> findByPlaceType(String placeType);

    Optional<Place> findByProviderAndProviderPlaceId(
            String provider,
            String providerPlaceId
    );

    List<Place> findByPartnerBusinessId(Long partnerBusinessId);

    List<Place> findByPartnerBusinessIdAndProvider(
            Long partnerBusinessId,
            String provider
    );

    /**
     * Lấy POI công khai theo điểm đến và loại địa điểm.
     * OSM được lấy trực tiếp.
     * PARTNER chỉ được lấy khi Business đã được duyệt, công khai và đang mở.
     */
    @Query(value = """
            SELECT p.*
            FROM places p
            LEFT JOIN partner_businesses b
                ON b.id = p.partner_business_id
            WHERE UPPER(TRIM(p.destination)) = UPPER(TRIM(:destination))
              AND UPPER(TRIM(p.place_type)) = UPPER(TRIM(:placeType))
              AND (
                    UPPER(TRIM(p.provider)) = 'OSM'
                    OR (
                        UPPER(TRIM(p.provider)) = 'PARTNER'
                        AND b.id IS NOT NULL
                        AND b.verification_status IN ('APPROVED', 'VERIFIED')
                        AND b.publication_status = 'PUBLISHED'
                        AND b.operating_status = 'OPEN'
                        AND b.deleted_at IS NULL
                    )
              )
            ORDER BY
                CASE
                    WHEN UPPER(TRIM(p.provider)) = 'PARTNER' THEN 0
                    ELSE 1
                END,
                p.id DESC
            """, nativeQuery = true)
    List<Place> findPublicPlacesByDestinationAndPlaceType(
            @Param("destination") String destination,
            @Param("placeType") String placeType
    );

    /**
     * Mọi Place PARTNER công khai theo loại, có tọa độ. Lọc điểm đến (khớp tên/bán kính) ở service
     * vì tên điểm đến của Trip và city của Business thường viết khác nhau ("Đà Lạt" / "TP. Đà Lạt").
     */
    @Query(value = """
            SELECT p.*
            FROM places p
            JOIN partner_businesses b
                ON b.id = p.partner_business_id
            WHERE UPPER(TRIM(p.provider)) = 'PARTNER'
              AND UPPER(TRIM(p.place_type)) = UPPER(TRIM(:placeType))
              AND p.latitude IS NOT NULL
              AND p.longitude IS NOT NULL
              AND b.verification_status IN ('APPROVED', 'VERIFIED')
              AND b.publication_status = 'PUBLISHED'
              AND b.operating_status = 'OPEN'
              AND b.deleted_at IS NULL
            ORDER BY p.id DESC
            LIMIT 500
            """, nativeQuery = true)
    List<Place> findEligiblePartnerPlacesByPlaceType(@Param("placeType") String placeType);

    /**
     * Fallback theo destination khi cần kiểm tra các loại dữ liệu cũ.
     * Vẫn áp dụng điều kiện công khai đối với PARTNER.
     */
    @Query(value = """
            SELECT p.*
            FROM places p
            LEFT JOIN partner_businesses b
                ON b.id = p.partner_business_id
            WHERE UPPER(TRIM(p.destination)) = UPPER(TRIM(:destination))
              AND (
                    UPPER(TRIM(p.provider)) = 'OSM'
                    OR (
                        UPPER(TRIM(p.provider)) = 'PARTNER'
                        AND b.id IS NOT NULL
                        AND b.verification_status IN ('APPROVED', 'VERIFIED')
                        AND b.publication_status = 'PUBLISHED'
                        AND b.operating_status = 'OPEN'
                        AND b.deleted_at IS NULL
                    )
              )
            ORDER BY
                CASE
                    WHEN UPPER(TRIM(p.provider)) = 'PARTNER' THEN 0
                    ELSE 1
                END,
                p.id DESC
            """, nativeQuery = true)
    List<Place> findPublicPlacesByDestination(
            @Param("destination") String destination
    );
}