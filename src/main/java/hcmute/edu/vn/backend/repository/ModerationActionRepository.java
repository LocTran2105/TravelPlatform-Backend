package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.ModerationAction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

    /** Nhật ký chung, mới nhất trước (nạp luôn người thực hiện). */
    @Query("""
            SELECT a
            FROM ModerationAction a
            LEFT JOIN FETCH a.actor
            ORDER BY a.id DESC
            """)
    List<ModerationAction> findRecent(Pageable pageable);

    /** Nhật ký của một nội dung, mới nhất trước. */
    @Query("""
            SELECT a
            FROM ModerationAction a
            LEFT JOIN FETCH a.actor
            WHERE a.targetType = :targetType AND a.targetId = :targetId
            ORDER BY a.id DESC
            """)
    List<ModerationAction> findByTarget(@Param("targetType") String targetType,
                                        @Param("targetId") Long targetId);

    long countByCreatedAtAfter(LocalDateTime after);
}
