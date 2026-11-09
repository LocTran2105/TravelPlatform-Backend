package hcmute.edu.vn.backend.repository;
import hcmute.edu.vn.backend.entity.PartnerAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PartnerAuditLogRepository extends JpaRepository<PartnerAuditLog, Long> {
    List<PartnerAuditLog> findTop100ByBusiness_IdOrderByCreatedAtDesc(Long businessId);
    List<PartnerAuditLog> findTop100ByPartner_IdOrderByCreatedAtDesc(Long partnerId);
}