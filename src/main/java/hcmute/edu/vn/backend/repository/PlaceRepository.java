package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlaceRepository extends JpaRepository<Place, Long> {
    List<Place> findByDestinationIgnoreCase(String destination);
    List<Place> findByDestinationIgnoreCaseAndPlaceTypeIgnoreCase(String destination, String placeType);
    List<Place> findByPlaceType(String placeType);
    Optional<Place> findByProviderAndProviderPlaceId(String provider, String providerPlaceId);
}