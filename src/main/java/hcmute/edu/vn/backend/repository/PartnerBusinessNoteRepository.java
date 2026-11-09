package hcmute.edu.vn.backend.repository;
import hcmute.edu.vn.backend.entity.PartnerBusinessNote;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface PartnerBusinessNoteRepository extends JpaRepository<PartnerBusinessNote, Long> {
    List<PartnerBusinessNote> findByBusiness_IdOrderByCreatedAtDesc(Long businessId);
    Optional<PartnerBusinessNote> findByIdAndBusiness_Id(Long id, Long businessId);
}