package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Report;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReportRepository extends JpaRepository<Report, Long> {

    // Lấy danh sách báo cáo theo trạng thái.
    Page<Report> findByStatus(
            String status,
            Pageable pageable
    );

    // Đếm số báo cáo theo trạng thái.
    long countByStatus(String status);

    // Lọc báo cáo theo loại đối tượng.
    Page<Report> findByTargetTypeAndStatus(
            String targetType,
            String status,
            Pageable pageable
    );

    // Khóa báo cáo trong quá trình Admin xử lý.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r
            FROM Report r
            JOIN FETCH r.reporter
            WHERE r.id = :reportId
            """)
    Optional<Report> findByIdForUpdate(
            @Param("reportId") Long reportId
    );
}