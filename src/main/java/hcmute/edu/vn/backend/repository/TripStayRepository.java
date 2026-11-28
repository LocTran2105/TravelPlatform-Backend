package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.TripStay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TripStayRepository extends JpaRepository<TripStay, Long> {
    List<TripStay> findByTripIdOrderByCheckInDateAscIdAsc(Long tripId);
}
