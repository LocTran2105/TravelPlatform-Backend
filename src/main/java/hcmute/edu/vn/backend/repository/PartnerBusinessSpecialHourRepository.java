package hcmute.edu.vn.backend.repository;
import hcmute.edu.vn.backend.entity.PartnerBusinessSpecialHour;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
public interface PartnerBusinessSpecialHourRepository extends JpaRepository<PartnerBusinessSpecialHour, Long> {
    List<PartnerBusinessSpecialHour> findByBusiness_IdAndSpecialDateBetweenOrderBySpecialDateAsc(Long businessId, LocalDate from, LocalDate to);
    Optional<PartnerBusinessSpecialHour> findByIdAndBusiness_Id(Long id, Long businessId);
    Optional<PartnerBusinessSpecialHour> findByBusiness_IdAndSpecialDate(Long businessId, LocalDate date);
}