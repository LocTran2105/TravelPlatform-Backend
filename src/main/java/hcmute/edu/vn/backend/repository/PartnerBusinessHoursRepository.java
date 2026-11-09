package hcmute.edu.vn.backend.repository;
import hcmute.edu.vn.backend.entity.PartnerBusinessHours;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface PartnerBusinessHoursRepository extends JpaRepository<PartnerBusinessHours, Long> {
    List<PartnerBusinessHours> findByBusiness_IdOrderByDayOfWeekAsc(Long businessId);
    Optional<PartnerBusinessHours> findByBusiness_IdAndDayOfWeek(Long businessId, Integer dayOfWeek);
    void deleteByBusiness_Id(Long businessId);
}