package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Image;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImageRepository extends JpaRepository<Image, Long> {
    // Lấy tất cả ảnh/video của một bài đăng hoặc một chuyến đi
    List<Image> findByEntityTypeAndEntityId(String entityType, Long entityId);
}