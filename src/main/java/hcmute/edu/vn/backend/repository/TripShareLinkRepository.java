package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.TripShareLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TripShareLinkRepository extends JpaRepository<TripShareLink, Long> {

    List<TripShareLink> findByTripId(Long tripId);

    Optional<TripShareLink> findByTripIdAndRole(Long tripId, String role);

    // Lấy luôn chuyến đi và chủ chuyến đi để dựng trang xem trước mà không bị lazy-load lỗi
    @Query("""
        SELECT l FROM TripShareLink l
        JOIN FETCH l.trip t
        JOIN FETCH t.user
        WHERE l.token = :token
        """)
    Optional<TripShareLink> findByTokenWithTrip(@Param("token") String token);
}
