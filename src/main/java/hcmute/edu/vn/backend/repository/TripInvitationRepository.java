package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.TripInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TripInvitationRepository extends JpaRepository<TripInvitation, Long> {

    // Xem trước lời mời (đọc, không khóa)
    @Query("""
        SELECT i FROM TripInvitation i
        JOIN FETCH i.trip t
        JOIN FETCH t.user
        JOIN FETCH i.invitedBy
        WHERE i.tokenHash = :hash
        """)
    Optional<TripInvitation> findByTokenHashWithTrip(@Param("hash") String hash);

    // Khóa dòng khi chấp nhận / từ chối để hai request cùng lúc không xử lý trùng
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM TripInvitation i WHERE i.tokenHash = :hash")
    Optional<TripInvitation> findByTokenHashForUpdate(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM TripInvitation i WHERE i.id = :id")
    Optional<TripInvitation> findByIdForUpdate(@Param("id") Long id);

    // Lời mời còn hiệu lực gửi tới một email (hiện trong app)
    @Query("""
        SELECT i FROM TripInvitation i
        JOIN FETCH i.trip t
        JOIN FETCH t.user
        JOIN FETCH i.invitedBy
        WHERE i.email = :email AND i.status = 'PENDING' AND i.expiresAt > :now
        ORDER BY i.id DESC
        """)
    List<TripInvitation> findActiveForEmail(@Param("email") String email,
                                            @Param("now") LocalDateTime now);

    // Lời mời đang chờ của một chuyến đi (kể cả đã hết hạn để chủ chuyến đi gửi lại)
    @Query("""
        SELECT i FROM TripInvitation i
        JOIN FETCH i.invitedBy
        WHERE i.trip.id = :tripId AND i.status = 'PENDING'
        ORDER BY i.id DESC
        """)
    List<TripInvitation> findPendingByTripId(@Param("tripId") Long tripId);

    Optional<TripInvitation> findFirstByTripIdAndEmailAndStatus(Long tripId, String email, String status);

    long countByTripIdAndStatus(Long tripId, String status);

    // Giới hạn tần suất: số email một người đã gửi (hoặc gửi lại) kể từ một thời điểm
    long countByInvitedByIdAndLastSentAtAfter(Long userId, LocalDateTime since);
}
