package hcmute.edu.vn.backend.dto;

import java.time.LocalDateTime;

/**
 * DTO cho trang quản trị viên quản lý kiểm duyệt viên (/api/admin/moderators).
 */
public final class ModeratorDto {

    private ModeratorDto() {
    }

    /** Body của POST /api/admin/moderators. */
    public record GrantRequest(Long userId) {
    }

    /** Thông tin tối thiểu của một tài khoản (không có mật khẩu, token, số điện thoại). */
    public record UserSummary(
            Long id,
            String username,
            String email,
            String fullName,
            String avatar,
            String role,
            String status,
            LocalDateTime createdAt
    ) {
    }
}
