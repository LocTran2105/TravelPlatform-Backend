package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    // Giữ method cũ để tương thích code đang sử dụng.
    List<Booking> findByUserId(Long userId);

    // Lấy booking của User, mới nhất trước.
    List<Booking> findByUser_IdOrderByCreatedAtDesc(Long userId);

    // Tìm booking thuộc đúng User.
    Optional<Booking> findByIdAndUser_Id(
            Long bookingId,
            Long userId
    );

    // Lấy toàn bộ booking của cơ sở kinh doanh.
    List<Booking> findByRoom_Business_IdOrderByCreatedAtDesc(
            Long businessId
    );

    // Kiểm tra booking thuộc đúng cơ sở kinh doanh.
    Optional<Booking> findByIdAndRoom_Business_Id(
            Long bookingId,
            Long businessId
    );

    // Lọc booking theo trạng thái.
    List<Booking> findByRoom_Business_IdAndStatusOrderByCreatedAtDesc(
            Long businessId,
            String status
    );

    /**
     * Kiểm tra booking bị trùng thời gian.
     *
     * Hai khoảng thời gian bị trùng khi:
     * checkIn cũ < checkOut mới
     * và checkOut cũ > checkIn mới.
     */
    @Query("""
            SELECT COUNT(b)
            FROM Booking b
            WHERE b.room.id = :roomId
              AND b.status IN :statuses
              AND b.checkIn < :requestedCheckOut
              AND b.checkOut > :requestedCheckIn
            """)
    long countConflictingBookings(
            @Param("roomId") Long roomId,
            @Param("statuses") List<String> statuses,
            @Param("requestedCheckOut") LocalDate requestedCheckOut,
            @Param("requestedCheckIn") LocalDate requestedCheckIn
    );
}