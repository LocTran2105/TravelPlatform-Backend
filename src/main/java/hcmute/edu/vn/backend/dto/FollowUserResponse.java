package hcmute.edu.vn.backend.dto;

/**
 * Một người trong danh sách followers / following.
 * Chỉ chứa thông tin công khai (không có email, số điện thoại, role).
 */
public record FollowUserResponse(
        Long id,
        String username,
        String fullName,
        String avatar,
        boolean followedByMe,   // người đang xem có theo dõi người này không
        boolean followingMe     // người này có theo dõi người đang xem không
) {
}