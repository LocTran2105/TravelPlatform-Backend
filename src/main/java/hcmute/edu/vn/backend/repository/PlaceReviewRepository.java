package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PlaceReview;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlaceReviewRepository extends JpaRepository<PlaceReview, Long> {
    List<PlaceReview> findByLocationNameOrderByCreatedAtDesc(String locationName);
}