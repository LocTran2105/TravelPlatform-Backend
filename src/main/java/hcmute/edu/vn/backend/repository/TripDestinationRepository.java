package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.TripDestination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TripDestinationRepository extends JpaRepository<TripDestination, Long> {

    // Giữ method cũ để tương thích với Service hiện tại.
    List<TripDestination> findByTripDayIdOrderByOrderIndexAsc(Long tripDayId);

    // Tên tường minh tương đương, chỉ rõ đường dẫn TripDestination.tripDay.id.
    List<TripDestination> findByTripDay_IdOrderByOrderIndexAsc(Long tripDayId);

    long countByPlace_Id(Long placeId);
}
