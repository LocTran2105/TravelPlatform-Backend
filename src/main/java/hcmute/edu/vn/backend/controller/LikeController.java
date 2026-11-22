package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.entity.UserLike;
import hcmute.edu.vn.backend.repository.UserLikeRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import hcmute.edu.vn.backend.service.PostAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/social/likes")
@RequiredArgsConstructor
public class LikeController {

    /**
     * Hiện chỉ POST có kiểm tra quyền xem nên chỉ mở cho POST.
     * Khi làm tim cho TRIP / REVIEW... thì thêm nhánh kiểm tra tương ứng rồi
     * mới thêm loại đó vào đây (trước đây chấp nhận MỌI chuỗi bất kỳ).
     */
    private static final String TYPE_POST = "POST";

    private final UserLikeRepository userLikeRepository;
    private final UserRepository userRepository;
    private final PostAccessService postAccessService;

    // API THÍCH (POST) - idempotent: thích rồi mà gọi lại vẫn OK
    @PostMapping("/{targetType}/{targetId}")
    public ResponseEntity<?> like(
            @PathVariable String targetType,
            @PathVariable Long targetId,
            Principal principal) {
        try {
            String type = requireSupportedType(targetType);
            User user = requireActiveUser(principal.getName());

            // 404 nếu bài không tồn tại / đã xóa / người này không có quyền xem.
            postAccessService.requireViewablePost(targetId, user);

            if (!userLikeRepository.existsByUserIdAndTargetTypeAndTargetId(user.getId(), type, targetId)) {
                try {
                    UserLike newLike = new UserLike();
                    newLike.setUser(user);
                    newLike.setTargetType(type);
                    newLike.setTargetId(targetId);
                    userLikeRepository.save(newLike);
                } catch (DataIntegrityViolationException ignored) {
                    // 2 request cùng lúc: UNIQUE (user, target) chặn bản thứ hai -> coi như đã thích.
                }
            }
            return ResponseEntity.ok(state(true, type, targetId));
        } catch (Exception e) {
            return error(e);
        }
    }

    // API BỎ THÍCH (DELETE) - idempotent
    @DeleteMapping("/{targetType}/{targetId}")
    public ResponseEntity<?> unlike(
            @PathVariable String targetType,
            @PathVariable Long targetId,
            Principal principal) {
        try {
            String type = requireSupportedType(targetType);
            User user = requireActiveUser(principal.getName());

            // Cố ý KHÔNG kiểm tra quyền xem: người dùng luôn được rút tim của mình.
            userLikeRepository.findByUserIdAndTargetTypeAndTargetId(user.getId(), type, targetId)
                    .ifPresent(userLikeRepository::delete);

            return ResponseEntity.ok(state(false, type, targetId));
        } catch (Exception e) {
            return error(e);
        }
    }

    // ------------------------------------------------------------------

    /** Trả số tim thật từ server để frontend đồng bộ lại thay vì tự cộng trừ. */
    private Map<String, Object> state(boolean liked, String type, Long targetId) {
        return Map.of(
                "liked", liked,
                "likeCount", userLikeRepository.countByTargetTypeAndTargetId(type, targetId)
        );
    }

    private String requireSupportedType(String targetType) {
        String type = targetType == null ? "" : targetType.trim().toUpperCase();
        if (!TYPE_POST.equals(type)) {
            throw new IllegalArgumentException("Loại đối tượng không được hỗ trợ: " + targetType);
        }
        return type;
    }

    private User requireActiveUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy người dùng."));
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalStateException("Tài khoản hiện không hoạt động.");
        }
        return user;
    }

    private ResponseEntity<String> error(Exception e) {
        if (e instanceof NoSuchElementException) {
            return ResponseEntity.status(404).body(e.getMessage());
        }
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        return ResponseEntity.internalServerError().body("Lỗi thao tác: " + e.getMessage());
    }
}