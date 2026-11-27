package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Notification;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientIdOrderByEventAtDescIdDesc(Long recipientId, Pageable pageable);

    Page<Notification> findByRecipientIdAndSeenFalseOrderByEventAtDescIdDesc(Long recipientId, Pageable pageable);

    long countByRecipientIdAndSeenFalse(Long recipientId);

    boolean existsByRecipientIdAndDedupeKey(Long recipientId, String dedupeKey);

    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    /** Khóa dòng khi gộp thông báo để hai lượt thích đồng thời không ghi đè nhau. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT n
            FROM Notification n
            WHERE n.recipientId = :recipientId AND n.dedupeKey = :dedupeKey
            """)
    Optional<Notification> findByRecipientAndDedupeKeyForUpdate(@Param("recipientId") Long recipientId,
                                                                @Param("dedupeKey") String dedupeKey);

    /** Số người đã thích bài (không tính chủ bài) - nguồn sự thật để gộp thông báo thích. */
    @Query("""
            SELECT COUNT(l)
            FROM UserLike l
            WHERE l.targetType = 'POST' AND l.targetId = :postId AND l.user.id <> :ownerId
            """)
    long countPostLikesExcludingOwner(@Param("postId") Long postId, @Param("ownerId") Long ownerId);

    @Modifying
    @Query("UPDATE Notification n SET n.seen = true WHERE n.recipientId = :recipientId AND n.seen = false")
    int markAllRead(@Param("recipientId") Long recipientId);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.id = :id AND n.recipientId = :recipientId")
    int deleteOwned(@Param("id") Long id, @Param("recipientId") Long recipientId);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.seen = true AND n.eventAt < :cutoff")
    int deleteReadOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
