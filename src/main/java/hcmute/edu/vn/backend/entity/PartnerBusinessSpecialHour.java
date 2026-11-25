package hcmute.edu.vn.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "partner_business_special_hours")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PartnerBusinessSpecialHour {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private PartnerBusiness business;

    @Column(name = "special_date", nullable = false)
    private LocalDate specialDate;

    @Column(name = "open_time")
    private LocalTime openTime;

    @Column(name = "close_time")
    private LocalTime closeTime;

    @Column(name = "is_closed", nullable = false)
    private Boolean closed = false;

    /** Lý do (nghỉ lễ, sự kiện...). Cột DB là reason VARCHAR(255); tên field giữ là note để không sửa service. */
    @Column(name = "reason", length = 255)
    private String note;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // Bảng partner_business_special_hours không có cột updated_at nên không ánh xạ.

    @PrePersist
    @PreUpdate
    void normalizeReason() {
        if (note != null) {
            String trimmed = note.trim();
            note = trimmed.isEmpty() ? null : (trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed);
        }
    }
}