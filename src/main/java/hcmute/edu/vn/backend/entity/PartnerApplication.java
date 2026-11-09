package hcmute.edu.vn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "partner_applications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PartnerApplication {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;
    @Column(name = "legal_name", length = 255)
    private String legalName;
    @Column(name = "tax_code", length = 50)
    private String taxCode;
    @Column(name = "contact_person", length = 150)
    private String contactPerson;
    @Column(name = "contact_email", length = 255)
    private String contactEmail;
    @Column(name = "contact_phone", length = 20)
    private String contactPhone;
    @Column(name = "business_address", length = 500)
    private String businessAddress;
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    @Column(name = "verification_status", nullable = false, length = 30)
    private String verificationStatus = "PENDING";
    @Column(name = "admin_note", columnDefinition = "TEXT")
    private String adminNote;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}