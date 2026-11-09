package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PartnerPromotion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PartnerPromotionRepository
        extends JpaRepository<PartnerPromotion, Long> {

    List<PartnerPromotion> findByBusiness_IdOrderByStartsAtDesc(Long businessId);

    Optional<PartnerPromotion> findByIdAndBusiness_Id(
            Long id,
            Long businessId
    );

    long countByBusiness_Partner_IdAndStatus(
            Long partnerId,
            String status
    );
}
