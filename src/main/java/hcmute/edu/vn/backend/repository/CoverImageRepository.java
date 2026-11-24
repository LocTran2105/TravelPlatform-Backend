package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.CoverImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CoverImageRepository extends JpaRepository<CoverImage, Long> {

    List<CoverImage> findByActiveTrueOrderBySortOrderAscIdAsc();

    Optional<CoverImage> findByIdAndActiveTrue(Long id);
}
