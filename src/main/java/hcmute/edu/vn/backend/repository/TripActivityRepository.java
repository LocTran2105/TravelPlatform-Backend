package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.TripActivity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TripActivityRepository extends JpaRepository<TripActivity, Long> {

    // Dòng mới nhất của chuyến đi (dùng để gộp các lần sửa liên tiếp)
    Optional<TripActivity> findFirstByTripIdOrderByIdDesc(Long tripId);

    // Trang đầu (mới nhất trước)
    List<TripActivity> findByTripIdOrderByIdDesc(Long tripId, Pageable pageable);

    // Các trang sau: lấy những dòng cũ hơn mốc `before` (keyset, không bị lệch khi có dòng mới chen vào)
    List<TripActivity> findByTripIdAndIdLessThanOrderByIdDesc(Long tripId, Long before, Pageable pageable);
}
