package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.TripComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TripCommentRepository extends JpaRepository<TripComment, Long> {
    // Lấy các comment gốc (không phải là reply) của một chuyến đi
    List<TripComment> findByTripIdAndParentCommentIsNullOrderByCreatedAtDesc(Long tripId);
}