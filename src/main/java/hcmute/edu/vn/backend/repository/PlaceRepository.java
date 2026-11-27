package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    /**
     * Tìm Place công khai theo tên/địa chỉ cho ô tìm kiếm của Trip.
     * Collation utf8mb4 (_ci) của MySQL không phân biệt hoa thường và dấu: "lung chung" khớp "Lưng Chừng".
     * PARTNER chỉ trả khi Business đã duyệt, công khai, đang mở; ưu tiên PARTNER, rồi cùng điểm đến của Trip.
     */
    @Query(value = """
            SELECT p.*
            FROM places p
            LEFT JOIN partner_businesses b
                ON b.id = p.partner_business_id
            WHERE p.latitude IS NOT NULL
              AND p.longitude IS NOT NULL
              AND (
                    p.name LIKE :pattern
                    OR p.address LIKE :pattern
                    OR p.name LIKE :firstSegment
                    OR p.address LIKE :firstSegment
              )
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
                -- Đã xác minh (PARTNER hoặc OSM đã liên kết với Business công khai) lên đầu
                CASE WHEN b.id IS NOT NULL
                          AND b.verification_status IN ('APPROVED', 'VERIFIED')
                          AND b.publication_status = 'PUBLISHED'
                          AND b.operating_status = 'OPEN'
                          AND b.deleted_at IS NULL THEN 0 ELSE 1 END,
                CASE WHEN :destination <> '' AND p.destination LIKE CONCAT('%', :destination, '%') THEN 0 ELSE 1 END,
                CASE WHEN p.name LIKE :pattern THEN 0 ELSE 1 END,
                p.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Place> searchPublicPlaces(
            @Param("pattern") String pattern,
            @Param("firstSegment") String firstSegment,
            @Param("destination") String destination,
            @Param("limit") int limit
    );

    /** POI OSM đã lưu, còn mới (fetched_at >= :since), đúng điểm đến + loại, có tọa độ. */
    @Query(value = """
            SELECT p.*
            FROM places p
            WHERE UPPER(TRIM(p.provider)) = 'OSM'
              AND UPPER(TRIM(p.destination)) = UPPER(TRIM(:destination))
              AND UPPER(TRIM(p.place_type)) = UPPER(TRIM(:placeType))
              AND p.latitude IS NOT NULL
              AND p.longitude IS NOT NULL
              AND p.fetched_at >= :since
            ORDER BY p.fetched_at DESC, p.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Place> findFreshOsmPlaces(
            @Param("destination") String destination,
            @Param("placeType") String placeType,
            @Param("since") LocalDateTime since,
            @Param("limit") int limit
    );

    /**
     * POI OSM đã lưu trong một khung tọa độ, đúng loại. Dùng khi Admin duyệt Business mà Overpass lỗi:
     * vẫn liên kết được với POI đã có trong DB thay vì phải tạo Place PARTNER trùng với OSM.
     */
    @Query(value = """
            SELECT p.*
            FROM places p
            WHERE UPPER(TRIM(p.provider)) = 'OSM'
              AND UPPER(TRIM(p.place_type)) = UPPER(TRIM(:placeType))
              AND p.latitude BETWEEN :minLat AND :maxLat
              AND p.longitude BETWEEN :minLng AND :maxLng
            LIMIT 500
            """, nativeQuery = true)
    List<Place> findOsmPlacesInBox(
            @Param("placeType") String placeType,
            @Param("minLat") double minLat,
            @Param("maxLat") double maxLat,
            @Param("minLng") double minLng,
            @Param("maxLng") double maxLng
    );

    /** Trong các Business được hỏi, những Business đang công khai (đã duyệt, đã xuất bản, đang mở, chưa xóa). */
    @Query(value = """
            SELECT b.id
            FROM partner_businesses b
            WHERE b.id IN (:businessIds)
              AND b.verification_status IN ('APPROVED', 'VERIFIED')
              AND b.publication_status = 'PUBLISHED'
              AND b.operating_status = 'OPEN'
              AND b.deleted_at IS NULL
            """, nativeQuery = true)
    List<Number> findPublicBusinessIds(@Param("businessIds") java.util.Collection<Long> businessIds);

    /** 1 nếu Place là PARTNER của Business đang công khai (đã duyệt, đã xuất bản, đang mở, chưa xóa). */
    @Query(value = """
            SELECT COUNT(*)
            FROM places p
            JOIN partner_businesses b ON b.id = p.partner_business_id
            WHERE p.id = :placeId
              AND UPPER(TRIM(p.provider)) = 'PARTNER'
              AND b.verification_status IN ('APPROVED', 'VERIFIED')
              AND b.publication_status = 'PUBLISHED'
              AND b.operating_status = 'OPEN'
              AND b.deleted_at IS NULL
            """, nativeQuery = true)
    long countPublicPartnerPlace(@Param("placeId") Long placeId);
}