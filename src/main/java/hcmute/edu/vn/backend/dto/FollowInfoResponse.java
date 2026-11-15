package hcmute.edu.vn.backend.dto;

/**
 * Thông tin follow của một hồ sơ, tính theo góc nhìn của người đang xem.
 *  relationship: SELF, NONE, FOLLOWING (mình theo dõi họ), FOLLOWED_BY (họ theo dõi mình), FRIENDS (theo dõi lẫn nhau)
 * Nếu người xem chưa đăng nhập: followedByMe = followingMe = false, relationship = NONE.
 */
public record FollowInfoResponse(
        String username,
        long followersCount,
        long followingCount,
        boolean followedByMe,
        boolean followingMe,
        String relationship
) {
}