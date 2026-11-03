package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.AiConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiConversationRepository extends JpaRepository<AiConversation, Long> {
    Optional<AiConversation> findByTripIdAndUserId(Long tripId, Long userId);
}