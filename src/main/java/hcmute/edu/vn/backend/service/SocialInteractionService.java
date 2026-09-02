package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.InteractionRequest;
import hcmute.edu.vn.backend.entity.Follow;
import hcmute.edu.vn.backend.entity.Share;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.entity.UserLike;
import hcmute.edu.vn.backend.repository.FollowRepository;
import hcmute.edu.vn.backend.repository.ShareRepository;
import hcmute.edu.vn.backend.repository.UserLikeRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
}