package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.CustomPlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CustomPlaceRepository extends JpaRepository<CustomPlace, Long> {
    List<CustomPlace> findByUserId(Long userId); // Lấy danh sách địa điểm của 1 user
}