package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.FollowInfoResponse;
import hcmute.edu.vn.backend.dto.FollowUserResponse;
import hcmute.edu.vn.backend.entity.Follow;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.FollowRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;

@Service
public class FollowService {

    private final FollowRepository followRepository;
    private final UserRepository userRepository;

    public FollowService(FollowRepository followRepository, UserRepository userRepository) {
        this.followRepository = followRepository;
        this.userRepository = userRepository;
    }

    /**
     * Theo dõi. Idempotent: gọi nhiều lần vẫn ra cùng kết quả "đã theo dõi".
     * Cố ý KHÔNG gắn @Transactional ở đây để lỗi trùng khóa (bấm đồng thời) không làm
     * hỏng transaction bên ngoài; mỗi lệnh repository tự chạy trong transaction riêng.
     */
    public FollowInfoResponse follow(String actorEmail, String targetUsername) {
        User actor = requireActiveActor(actorEmail);
        User target = requireActiveUser(targetUsername);

        if (actor.getId().equals(target.getId())) {
            throw new IllegalArgumentException("Bạn không thể tự theo dõi chính mình");
        }

        if (!followRepository.existsByFollowerIdAndFollowedId(actor.getId(), target.getId())) {
            try {
                followRepository.saveAndFlush(Follow.builder().follower(actor).followed(target).build());
            } catch (DataIntegrityViolationException ignored) {
                // Hai yêu cầu đến cùng lúc: yêu cầu kia đã tạo bản ghi, kết quả vẫn là "đã theo dõi".
            }
        }
        return buildInfo(target, actor);
    }

    /**
     * Hủy theo dõi. Idempotent: chưa theo dõi thì không có gì xảy ra.
     * Vẫn cho hủy theo dõi người đã bị khóa để dọn quan hệ cũ.
     */
    public FollowInfoResponse unfollow(String actorEmail, String targetUsername) {
        User actor = requireActiveActor(actorEmail);
        User target = requireUser(targetUsername);

        followRepository.findByFollowerIdAndFollowedId(actor.getId(), target.getId())
                .ifPresent(followRepository::delete);

        return buildInfo(target, actor);
    }

    /** Gỡ một người khỏi danh sách Followers của mình. Idempotent. */
    public FollowInfoResponse removeFollower(String myEmail, String followerUsername) {
        User me = requireActiveActor(myEmail);
        User follower = requireUser(followerUsername);

        followRepository.findByFollowerIdAndFollowedId(follower.getId(), me.getId())
                .ifPresent(followRepository::delete);

        return buildInfo(me, me);
    }

    /** Số đếm + quan hệ giữa người xem (có thể null) và hồ sơ đang xem. */
    @Transactional(readOnly = true)
    public FollowInfoResponse getFollowInfo(String viewerEmail, String targetUsername) {
        User target = requireActiveUser(targetUsername);
        return buildInfo(target, findViewer(viewerEmail));
    }

    @Transactional(readOnly = true)
    public Page<FollowUserResponse> getFollowers(String viewerEmail, String targetUsername, Pageable pageable) {
        User target = requireActiveUser(targetUsername);
        User viewer = findViewer(viewerEmail);
        Page<Follow> page = followRepository.findByFollowed(target, pageable);
        return toResponsePage(page, Follow::getFollower, viewer);
    }

    @Transactional(readOnly = true)
    public Page<FollowUserResponse> getFollowing(String viewerEmail, String targetUsername, Pageable pageable) {
        User target = requireActiveUser(targetUsername);
        User viewer = findViewer(viewerEmail);
        Page<Follow> page = followRepository.findByFollower(target, pageable);
        return toResponsePage(page, Follow::getFollowed, viewer);
    }

    // ------------------------------------------------------------------ helpers

    private FollowInfoResponse buildInfo(User target, User viewer) {
        boolean followedByMe = false;
        boolean followingMe = false;
        String relationship = "NONE";

        if (viewer != null) {
            if (viewer.getId().equals(target.getId())) {
                relationship = "SELF";
            } else {
                followedByMe = followRepository.existsByFollowerIdAndFollowedId(viewer.getId(), target.getId());
                followingMe = followRepository.existsByFollowerIdAndFollowedId(target.getId(), viewer.getId());
                if (followedByMe && followingMe) {
                    relationship = "FRIENDS";
                } else if (followedByMe) {
                    relationship = "FOLLOWING";
                } else if (followingMe) {
                    relationship = "FOLLOWED_BY";
                }
            }
        }

        return new FollowInfoResponse(
                target.getUsername(),
                followRepository.countByFollowedId(target.getId()),
                followRepository.countByFollowerId(target.getId()),
                followedByMe,
                followingMe,
                relationship
        );
    }

    // Tính cờ quan hệ cho cả trang bằng 2 truy vấn gộp (thay vì 2 truy vấn cho mỗi dòng).
    private Page<FollowUserResponse> toResponsePage(Page<Follow> page, Function<Follow, User> pick, User viewer) {
        Set<Long> iFollow = new HashSet<>();
        Set<Long> followMe = new HashSet<>();

        if (viewer != null && !page.getContent().isEmpty()) {
            List<Long> ids = page.getContent().stream().map(f -> pick.apply(f).getId()).toList();
            iFollow.addAll(followRepository.findFollowedIdsByFollowerAndIdIn(viewer.getId(), ids));
            followMe.addAll(followRepository.findFollowerIdsByFollowedAndIdIn(viewer.getId(), ids));
        }

        return page.map(f -> {
            User u = pick.apply(f);
            boolean self = viewer != null && viewer.getId().equals(u.getId());
            return new FollowUserResponse(
                    u.getId(),
                    u.getUsername(),
                    u.getFullName(),
                    u.getAvatar() != null ? u.getAvatar() : "",
                    !self && iFollow.contains(u.getId()),
                    !self && followMe.contains(u.getId())
            );
        });
    }

    private User findViewer(String viewerEmail) {
        return viewerEmail == null ? null : userRepository.findByEmail(viewerEmail).orElse(null);
    }

    private User requireActiveActor(String email) {
        User actor = userRepository.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy tài khoản của bạn"));
        if (!"ACTIVE".equalsIgnoreCase(actor.getStatus())) {
            throw new IllegalStateException("Tài khoản của bạn hiện không hoạt động");
        }
        return actor;
    }

    // Tìm theo username (không phân biệt hoa thường), bất kể trạng thái tài khoản.
    private User requireUser(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByUsername(normalized)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy người dùng"));
    }

    // Như trên nhưng tài khoản bị khóa/ngừng hoạt động được coi như không tồn tại.
    private User requireActiveUser(String username) {
        User user = requireUser(username);
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new NoSuchElementException("Không tìm thấy người dùng");
        }
        return user;
    }
}