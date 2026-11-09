package hcmute.edu.vn.backend.repository;
import hcmute.edu.vn.backend.entity.PartnerMenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface PartnerMenuCategoryRepository extends JpaRepository<PartnerMenuCategory, Long> {
    List<PartnerMenuCategory> findByBusiness_IdOrderBySortOrderAscIdAsc(Long businessId);
    Optional<PartnerMenuCategory> findByIdAndBusiness_Id(Long id, Long businessId);
}