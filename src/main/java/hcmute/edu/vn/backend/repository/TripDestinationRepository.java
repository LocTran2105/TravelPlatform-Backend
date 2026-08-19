package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.TripDestination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TripDestinationRepository extends JpaRepository<TripDestination, Long> {
    List<TripDestination> findByTripDayIdOrderByOrderIndexAsc(Long tripDayId);
}