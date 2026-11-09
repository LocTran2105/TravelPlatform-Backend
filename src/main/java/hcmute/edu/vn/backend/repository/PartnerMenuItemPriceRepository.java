package hcmute.edu.vn.backend.repository;
import hcmute.edu.vn.backend.entity.PartnerMenuItemPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface PartnerMenuItemPriceRepository extends JpaRepository<PartnerMenuItemPrice, Long> {
    List<PartnerMenuItemPrice> findByMenuItem_IdOrderByEffectiveFromDesc(Long menuItemId);
    Optional<PartnerMenuItemPrice> findTopByMenuItem_IdAndEffectiveToIsNullOrderByEffectiveFromDesc(Long menuItemId);
}