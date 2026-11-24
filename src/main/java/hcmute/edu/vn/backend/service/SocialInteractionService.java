package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.InteractionRequest;
import hcmute.edu.vn.backend.dto.UserMinimalDTO;
import hcmute.edu.vn.backend.entity.Follow;
import hcmute.edu.vn.backend.entity.Share;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.entity.UserLike;
import hcmute.edu.vn.backend.repository.FollowRepository;
import hcmute.edu.vn.backend.repository.ShareRepository;
import hcmute.edu.vn.backend.repository.UserLikeRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class SocialInteractionService {

    @Autowired
    private UserLikeRepository userLikeRepository;
    @Autowired
    private FollowRepository followRepository;
    @Autowired
    private ShareRepository shareRepository;
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private hcmute.edu.vn.backend.repository.TripRepository tripRepository;

    @Autowired
    private hcmute.edu.vn.backend.repository.TripCommentRepository tripCommentRepository;

    // 1. Logic Thả tim / Hủy thả tim (Toggle Like)
    public String toggleLike(InteractionRequest request, String userEmail) throws Exception {
        // 1. KIỂM TRA ĐỐI TƯỢNG CÓ TỒN TẠI KHÔNG TRƯỚC KHI LÀM BƯỚC TIẾP THEO
        validateTargetExists(request.getTargetType(), request.getTargetId());

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Người dùng không tồn tại!"));

        Optional<UserLike> existingLike = userLikeRepository
                .findByUserIdAndTargetTypeAndTargetId(user.getId(), request.getTargetType(), request.getTargetId());

        if (existingLike.isPresent()) {
            userLikeRepository.delete(existingLike.get());
            return "Đã bỏ thích (Unlike) thành công!";
        } else {
            UserLike newLike = new UserLike();
            newLike.setUser(user);
            newLike.setTargetType(request.getTargetType());
            newLike.setTargetId(request.getTargetId());
            userLikeRepository.save(newLike);
            return "Đã thích (Like) thành công!";
        }
    }

    // 2. Logic Theo dõi / Hủy theo dõi người dùng
    public String toggleFollow(Long followedId, String followerEmail) throws Exception {
        User follower = userRepository.findByEmail(followerEmail)
                .orElseThrow(() -> new Exception("Người dùng không tồn tại!"));

        User followed = userRepository.findById(followedId)
                .orElseThrow(() -> new Exception("Người dùng bạn muốn theo dõi không tồn tại!"));

        if (follower.getId().equals(followed.getId())) {
            throw new Exception("Bạn không thể tự theo dõi chính mình!");
        }

        Optional<Follow> existingFollow = followRepository.findByFollowerIdAndFollowedId(follower.getId(), followed.getId());

        if (existingFollow.isPresent()) {
            followRepository.delete(existingFollow.get());
            return "Đã hủy theo dõi (Unfollow) " + followed.getFullName();
        } else {
            Follow follow = new Follow();
            follow.setFollower(follower);
            follow.setFollowed(followed);
            followRepository.save(follow);
            return "Đã theo dõi (Follow) " + followed.getFullName();
        }
    }

    // 3. Logic Ghi nhận lượt chia sẻ
    public String recordShare(InteractionRequest request, String userEmail) throws Exception {
        validateTargetExists(request.getTargetType(), request.getTargetId());
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Người dùng không tồn tại!"));

        Share share = new Share();
        share.setUser(user);
        share.setTargetType(request.getTargetType());
        share.setTargetId(request.getTargetId());
        share.setPlatform(request.getPlatform());

        shareRepository.save(share);
        return "Ghi nhận lượt chia sẻ thành công!";
    }
    // Hàm này sẽ ném ra lỗi nếu ID ảo
    private void validateTargetExists(String targetType, Long targetId) throws Exception {
        switch (targetType.toUpperCase()) {
            case "TRIP":
                if (!tripRepository.existsById(targetId)) {
                    throw new Exception("Chuyến đi không tồn tại!");
                }
                break;
            case "TRIP_COMMENT":
                if (!tripCommentRepository.existsById(targetId)) {
                    throw new Exception("Bình luận không tồn tại!");
                }
                break;
            // TODO: Sau này làm thêm module Khách sạn, Bản đồ thì mở comment dưới đây
            // case "HOTEL":
            //     if (!hotelRepository.existsById(targetId)) throw new Exception("Khách sạn không tồn tại!");
            //     break;
            // case "CUSTOM_PLACE":
            //     if (!customPlaceRepository.existsById(targetId)) throw new Exception("Địa điểm không tồn tại!");
            //     break;
            default:
                throw new Exception("Loại đối tượng (targetType) không được hỗ trợ!");
        }
    }

    // FOllow
    @Transactional
    public boolean toggleFollow(String followerEmail, String targetUsername) {
        User follower = userRepository.findByEmail(followerEmail)
                .orElseThrow(() -> new RuntimeException("Lỗi xác thực người dùng hiện tại"));

        User targetUser = userRepository.findByUsername(targetUsername) // Sử dụng username mới bổ sung
                .orElseThrow(() -> new RuntimeException("Không tìm thấy người dùng đích"));

        if (follower.getId().equals(targetUser.getId())) {
            throw new IllegalArgumentException("Không thể tự theo dõi chính mình");
        }

        Optional<Follow> existingFollow = followRepository.findByFollowerAndFollowed(follower, targetUser);

        if (existingFollow.isPresent()) {
            // Đã follow rồi -> Xóa (Unfollow)
            followRepository.delete(existingFollow.get());
            return false; // Trả về false nghĩa là trạng thái hiện tại: Chưa follow
        } else {
            // Chưa follow -> Thêm mới (Follow)
            Follow newFollow = Follow.builder()
                    .follower(follower)
                    .followed(targetUser)
                    .build();
            followRepository.save(newFollow);
            return true; // Trả về true nghĩa là trạng thái hiện tại: Đã follow
        }
    }

    /**
     * Lấy danh sách Followers (Những người theo dõi targetUser)
     */
    public Page<UserMinimalDTO> getFollowers(String currentUserEmail, String targetUsername, Pageable pageable) {
        User targetUser = userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // currentUser có thể null nếu người dùng chưa đăng nhập, nhưng nếu có đăng nhập, ta kiểm tra xem họ có follow các user trong danh sách này không
        User currentUser = null;
        if (currentUserEmail != null) {
            currentUser = userRepository.findByEmail(currentUserEmail).orElse(null);
        }

        Page<Follow> follows = followRepository.findByFollowed(targetUser, pageable);

        final User finalCurrentUser = currentUser;
        return follows.map(follow -> mapToUserMinimalDTO(follow.getFollower(), finalCurrentUser));
    }

    /**
     * Lấy danh sách Following (Những người targetUser đang theo dõi)
     */
    public Page<UserMinimalDTO> getFollowing(String currentUserEmail, String targetUsername, Pageable pageable) {
        User targetUser = userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> new RuntimeException("User not found"));

        User currentUser = null;
        if (currentUserEmail != null) {
            currentUser = userRepository.findByEmail(currentUserEmail).orElse(null);
        }

        Page<Follow> follows = followRepository.findByFollower(targetUser, pageable);

        final User finalCurrentUser = currentUser;
        return follows.map(follow -> mapToUserMinimalDTO(follow.getFollowed(), finalCurrentUser));
    }

    // Hàm phụ trợ Map User Entity sang DTO
    private UserMinimalDTO mapToUserMinimalDTO(User user, User currentUser) {
        boolean isFollowedByMe = false;
        boolean isFollowingMe = false;

        if (currentUser != null && !currentUser.getId().equals(user.getId())) {
            // Kiểm tra xem Mình có follow họ không
            isFollowedByMe = followRepository.existsByFollowerAndFollowed(currentUser, user);
            // Kiểm tra xem Họ có follow Mình không
            isFollowingMe = followRepository.existsByFollowerAndFollowed(user, currentUser);
        }

        return UserMinimalDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .avatar(user.getAvatar())
                .FollowedByMe(isFollowedByMe)
                .FollowingMe(isFollowingMe)
                .build();
    }

    //Xoa theo doi
    // 4. Logic Xóa (Gỡ) người theo dõi (Remove Follower)
    @Transactional
    public void removeFollower(String followedEmail, String followerUsername) throws Exception {
        // 1. Lấy ra bản thân bạn (người được theo dõi)
        User followed = userRepository.findByEmail(followedEmail)
                .orElseThrow(() -> new Exception("Lỗi xác thực người dùng hiện tại"));

        // 2. Lấy ra người mà bạn muốn xóa khỏi danh sách follower
        User follower = userRepository.findByUsername(followerUsername)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng cần xóa"));

        // 3. Tìm bản ghi Follow tương ứng
        Follow existingFollow = followRepository.findByFollowerAndFollowed(follower, followed)
                .orElseThrow(() -> new Exception("Người này không theo dõi bạn"));

        // 4. Xóa bản ghi đó
        followRepository.delete(existingFollow);
    }
}