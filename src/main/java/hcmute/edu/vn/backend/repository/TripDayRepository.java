package hcmute.edu.vn.backend.repository;


import hcmute.edu.vn.backend.entity.TripDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TripDayRepository extends JpaRepository<TripDay, Long> {
    List<TripDay> findByTripId(Long tripId);
}