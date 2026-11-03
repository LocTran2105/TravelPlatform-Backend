package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.RecommendationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RecommendationEventRepository extends JpaRepository<RecommendationEvent, Long> {
}