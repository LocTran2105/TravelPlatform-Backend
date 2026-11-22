package hcmute.edu.vn.backend.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Các DTO của chức năng lời mời tham gia chuyến đi. */
public final class InvitationDtos {

    private InvitationDtos() {}

    /**
     * Body của POST /api/trips/{tripId}/invitations.
     * userIds: mời theo người dùng đã có tài khoản (server tự lấy email, không lộ email cho người mời).
     */
    public record InviteRequest(List<String> emails, List<Long> userIds, String role) {}

    /** Body của POST /api/invitations/accept|decline */
    public record TokenRequest(String token) {}

    /** Thông tin công khai của lời mời (người cầm link xem trước khi đăng nhập). */
    public record Preview(
            String status,          // PENDING, ACCEPTED, DECLINED, REVOKED, EXPIRED
            String tripTitle,
            String destination,
            LocalDate startDate,
            LocalDate endDate,
            String inviterName,
            String role,
            String maskedEmail,     // vd: l***@gmail.com
            boolean accountExists,  // đã có tài khoản với email này chưa
            LocalDateTime expiresAt) {}

    /** Lời mời đang chờ của người dùng hiện tại (hiện trong app). */
    public record Mine(
            Long id,
            Long tripId,
            String tripTitle,
            String destination,
            LocalDate startDate,
            LocalDate endDate,
            String inviterName,
            String inviterAvatar,
            String role,
            LocalDateTime expiresAt) {}

    /** Một dòng trong danh sách lời mời của chuyến đi. */
    public record TripInvitationInfo(
            Long id,
            String email,
            String role,
            String status,          // PENDING hoặc EXPIRED
            String invitedByName,
            LocalDateTime expiresAt,
            LocalDateTime lastSentAt,
            int sendCount) {}

    public record InviteResult(List<TripInvitationInfo> invited, List<String> skipped) {}

    public record AcceptResult(Long tripId, String status) {}
}
