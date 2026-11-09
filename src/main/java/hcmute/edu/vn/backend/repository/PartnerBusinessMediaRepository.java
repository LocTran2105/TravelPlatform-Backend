package hcmute.edu.vn.backend.repository;
import hcmute.edu.vn.backend.entity.PartnerBusinessMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface PartnerBusinessMediaRepository extends JpaRepository<PartnerBusinessMedia, Long> {
    List<PartnerBusinessMedia> findByBusiness_IdOrderBySortOrderAscIdAsc(Long businessId);
    Optional<PartnerBusinessMedia> findByIdAndBusiness_Id(Long id, Long businessId);
    long countByBusiness_Id(Long businessId);
}