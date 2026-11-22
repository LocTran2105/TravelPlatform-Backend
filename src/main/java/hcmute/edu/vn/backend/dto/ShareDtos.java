package hcmute.edu.vn.backend.dto;

import java.time.LocalDate;

/** DTO của chức năng chia sẻ chuyến đi (nút Share). */
public final class ShareDtos {

    private ShareDtos() {}

    /** Hai token của link chia sẻ. Frontend ghép thành {origin}/share/{token}. */
    public record ShareLinks(String editorToken, String viewerToken) {}

    /** Thông tin công khai hiện ở trang /share/:token trước khi đăng nhập. */
    public record SharePreview(
            String tripTitle,
            String destination,
            LocalDate startDate,
            LocalDate endDate,
            String ownerName,
            String role) {}

    public record JoinRequest(String token) {}

    /** status: JOINED (vừa vào), ALREADY (đã là thành viên), OWNER (chính là chủ chuyến đi). */
    public record JoinResult(Long tripId, String role, String status) {}

    public record RoleRequest(String role) {}

    /** Một dòng trong "Quản lý bạn đồng hành". memberId = null với chủ chuyến đi. */
    public record MemberInfo(
            Long memberId,
            Long userId,
            String username,
            String fullName,
            String avatar,
            String role,      // OWNER, EDITOR, VIEWER
            boolean owner) {}
}
