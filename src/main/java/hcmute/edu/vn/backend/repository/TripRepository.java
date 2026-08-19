package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long> {

    // Lấy toàn bộ chuyến đi của một người dùng cụ thể
    List<Trip> findByUserId(Long userId);

    // Dành cho Mạng xã hội: Lấy các chuyến đi được bật Public VÀ đã được Admin duyệt (APPROVED)
    List<Trip> findByIsPublicTrueAndPublicStatus(String publicStatus);
}