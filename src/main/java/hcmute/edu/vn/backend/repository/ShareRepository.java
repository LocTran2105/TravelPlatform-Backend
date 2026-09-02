package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Share;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShareRepository extends JpaRepository<Share, Long> {
    long countByTargetTypeAndTargetId(String targetType, Long targetId);
}