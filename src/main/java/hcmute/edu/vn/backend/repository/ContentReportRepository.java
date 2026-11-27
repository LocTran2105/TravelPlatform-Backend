package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.ContentReport;
import hcmute.edu.vn.backend.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ContentReportRepository extends JpaRepository<ContentReport, Long> {

    boolean existsByReporterIdAndTargetTypeAndTargetId(Long reporterId, String targetType, Long targetId);

    long countByReporterIdAndCreatedAtAfter(Long reporterId, LocalDateTime after);

    long countByStatus(String status);

    long countByTargetTypeAndTargetIdAndStatus(String targetType, Long targetId, String status);

    /** Toàn bộ báo cáo của một nội dung (mới nhất trước), nạp luôn người báo cáo. */
    @Query("""
            SELECT r
            FROM ContentReport r
            JOIN FETCH r.reporter
            WHERE r.targetType = :targetType AND r.targetId = :targetId
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    List<ContentReport> findAllByTarget(@Param("targetType") String targetType,
                                        @Param("targetId") Long targetId);

    /**
     * Hàng đợi: mỗi nội dung đang có báo cáo chờ xử lý là MỘT dòng.
     * [targetType, targetId, số báo cáo chờ, thời điểm báo cáo mới nhất]
     * Nhiều báo cáo nhất lên đầu, bằng nhau thì mới nhất lên đầu.
     */
    @Query("""
            SELECT r.targetType, r.targetId, COUNT(r), MAX(r.createdAt)
            FROM ContentReport r
            WHERE r.status = 'PENDING'
              AND (:targetType IS NULL OR r.targetType = :targetType)
            GROUP BY r.targetType, r.targetId
            ORDER BY COUNT(r) DESC, MAX(r.createdAt) DESC, r.targetId DESC
            """)
    List<Object[]> findPendingGroups(@Param("targetType") String targetType, Pageable pageable);

    /** [targetType, số nội dung khác nhau đang có báo cáo chờ] */
    @Query("""
            SELECT r.targetType, COUNT(DISTINCT r.targetId)
            FROM ContentReport r
            WHERE r.status = 'PENDING'
            GROUP BY r.targetType
            """)
    List<Object[]> countPendingGroupsByType();

    /** [reasonCode, số báo cáo chờ] của một nội dung. */
    @Query("""
            SELECT r.reasonCode, COUNT(r)
            FROM ContentReport r
            WHERE r.targetType = :targetType AND r.targetId = :targetId AND r.status = 'PENDING'
            GROUP BY r.reasonCode
            """)
    List<Object[]> countPendingReasons(@Param("targetType") String targetType,
                                       @Param("targetId") Long targetId);

    /** Đóng toàn bộ báo cáo chờ của một nội dung. Trả về số báo cáo đã đóng. */
    @Modifying
    @Query("""
            UPDATE ContentReport r
            SET r.status = :status, r.resolution = :resolution,
                r.reviewedBy = :reviewer, r.reviewedAt = :reviewedAt
            WHERE r.targetType = :targetType AND r.targetId = :targetId AND r.status = 'PENDING'
            """)
    int closePending(@Param("targetType") String targetType,
                     @Param("targetId") Long targetId,
                     @Param("status") String status,
                     @Param("resolution") String resolution,
                     @Param("reviewer") User reviewer,
                     @Param("reviewedAt") LocalDateTime reviewedAt);
}
