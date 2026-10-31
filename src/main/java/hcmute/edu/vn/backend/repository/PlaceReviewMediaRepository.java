package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PlaceReviewMedia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PlaceReviewMediaRepository extends JpaRepository<PlaceReviewMedia, Long> {
    // Lấy media của nhiều đánh giá trong 1 query (tránh N+1)
    List<PlaceReviewMedia> findByReviewIdInOrderByIdAsc(Collection<Long> reviewIds);

    void deleteByReviewId(Long reviewId);
}