package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.TripMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TripMemberRepository extends JpaRepository<TripMember, Long> {

    List<TripMember> findByTripIdAndStatus(Long tripId, String status);

    Optional<TripMember> findByTripIdAndUserId(Long tripId, Long userId);

    boolean existsByTripIdAndUserEmailIgnoreCaseAndStatus(Long tripId, String email, String status);

    Optional<TripMember> findByTripIdAndUserEmailIgnoreCaseAndStatus(Long tripId, String email, String status);

    // Số thành viên "tham gia thật": không tính người chỉ xem
    long countByTripIdAndStatusAndRoleNot(Long tripId, String status, String role);

    long countByTripIdAndStatus(Long tripId, String status);

    // Hai hàm cũ giữ nguyên tên để ExpenseService, TripService... không phải sửa,
    // nhưng nay chỉ tính thành viên đã chấp nhận (ACCEPTED).
    default List<TripMember> findByTripId(Long tripId) {
        return findByTripIdAndStatus(tripId, "ACCEPTED");
    }

    default boolean existsByTripIdAndUserEmail(Long tripId, String email) {
        return existsByTripIdAndUserEmailIgnoreCaseAndStatus(tripId, email, "ACCEPTED");
    }
}
