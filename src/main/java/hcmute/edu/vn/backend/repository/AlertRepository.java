package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Alert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AlertRepository extends JpaRepository<Alert, Long> {
    List<Alert> findByStatus(String status); // Lấy danh sách cảnh báo theo trạng thái (thường để lấy list APPROVED hiển thị lên bản đồ)
}