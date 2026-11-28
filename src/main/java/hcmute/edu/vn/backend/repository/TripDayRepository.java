package hcmute.edu.vn.backend.repository;


import hcmute.edu.vn.backend.entity.TripDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

@Repository
public interface TripDayRepository extends JpaRepository<TripDay, Long> {
    List<TripDay> findByTripId(Long tripId);

    /** Các ngày + điểm đến của nhiều chuyến đi trong một câu SQL (chụp lịch trình cho bài viết). */
    @Query("""
            SELECT DISTINCT d FROM TripDay d
            LEFT JOIN FETCH d.destinations
            WHERE d.trip.id IN :tripIds
            """)
    List<TripDay> findAllWithDestinationsByTripIdIn(@Param("tripIds") Collection<Long> tripIds);
}