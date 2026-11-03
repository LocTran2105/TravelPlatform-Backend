package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "user_preferences")
@Getter
@Setter
public class UserPreference {
    @Id
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId // Dùng chung ID với bảng users
    @JoinColumn(name = "user_id")
    private User user;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preference_tags")
    private List<String> preferenceTags;

    @Column(name = "preferred_accommodation", length = 30)
    private String preferredAccommodation;

    @Column(name = "preferred_transport", length = 30)
    private String preferredTransport;

    @Column(name = "travel_style", length = 30)
    private String travelStyle;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
