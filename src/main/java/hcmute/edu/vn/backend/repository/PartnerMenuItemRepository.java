package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PartnerMenuItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PartnerMenuItemRepository
        extends JpaRepository<PartnerMenuItem, Long> {

    List<PartnerMenuItem> findByCategory_IdOrderBySortOrderAscIdAsc(
            Long categoryId
    );

    Optional<PartnerMenuItem> findByIdAndCategory_Business_Id(
            Long itemId,
            Long businessId
    );

    List<PartnerMenuItem> findByCategory_Business_IdOrderBySortOrderAscIdAsc(
            Long businessId
    );

    long countByCategory_Business_Partner_Id(Long partnerId);
}
