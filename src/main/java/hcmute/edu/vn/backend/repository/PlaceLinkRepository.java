package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Repository used only for explicit, Admin-confirmed Partner/OSM associations. */
@Repository
public interface PlaceLinkRepository extends JpaRepository<Place, Long> {

    List<Place> findByPartnerBusinessIdAndProvider(Long partnerBusinessId, String provider);

    List<Place> findByPartnerBusinessIdIn(java.util.Collection<Long> partnerBusinessIds);

    Optional<Place> findByProviderAndProviderPlaceId(String provider, String providerPlaceId);

    @Query("""
            SELECT p FROM Place p
            WHERE p.provider = 'OSM'
              AND p.latitude IS NOT NULL
              AND p.longitude IS NOT NULL
              AND p.latitude BETWEEN :minLatitude AND :maxLatitude
              AND p.longitude BETWEEN :minLongitude AND :maxLongitude
            """)
    List<Place> findOsmPlacesNear(
            @Param("minLatitude") BigDecimal minLatitude,
            @Param("maxLatitude") BigDecimal maxLatitude,
            @Param("minLongitude") BigDecimal minLongitude,
            @Param("maxLongitude") BigDecimal maxLongitude
    );
}
