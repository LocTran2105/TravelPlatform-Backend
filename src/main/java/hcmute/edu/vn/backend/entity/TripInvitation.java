package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Lời mời tham gia chuyến đi gửi qua email.
 * Chỉ lưu bản băm (SHA-256) của token; token gốc chỉ có trong email.
 */
@Entity
@Table(name = "trip_invitations")
@Getter
@Setter
@NoArgsConstructor
public class TripInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "role", nullable = false, length = 20)
    private String role = "EDITOR"; // EDITOR, VIEWER

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING"; // PENDING, ACCEPTED, DECLINED, REVOKED

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by", nullable = false)
    private User invitedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_by")
    private User acceptedBy;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "last_sent_at", nullable = false)
    private LocalDateTime lastSentAt;

    @Column(name = "send_count", nullable = false)
    private Integer sendCount = 1;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
