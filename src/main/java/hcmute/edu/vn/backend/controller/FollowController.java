package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.FollowInfoResponse;
import hcmute.edu.vn.backend.dto.FollowUserResponse;
import hcmute.edu.vn.backend.service.FollowService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.NoSuchElementException;

/**
 * API follow, thiết kế theo từng hành động rõ ràng (không dùng toggle):
 *   POST   /api/social/users/{username}/follow           theo dõi        (idempotent)
 *   DELETE /api/social/users/{username}/follow           hủy theo dõi    (idempotent)
 *   GET    /api/social/users/{username}/follow-info      số đếm + quan hệ với người xem
 *   GET    /api/social/users/{username}/followers        danh sách followers (mới nhất trước)
 *   GET    /api/social/users/{username}/following        danh sách following (mới nhất trước)
 *   DELETE /api/social/users/{username}/remove-follower  gỡ người này khỏi followers của mình
 */
@RestController
@RequestMapping("/api/social/users/{username}")
public class FollowController {

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    @PostMapping("/follow")
    public ResponseEntity<FollowInfoResponse> follow(@PathVariable String username, Principal principal) {
        return ResponseEntity.ok(followService.follow(principal.getName(), username));
    }

    @DeleteMapping("/follow")
    public ResponseEntity<FollowInfoResponse> unfollow(@PathVariable String username, Principal principal) {
        return ResponseEntity.ok(followService.unfollow(principal.getName(), username));
    }

    @GetMapping("/follow-info")
    public ResponseEntity<FollowInfoResponse> followInfo(@PathVariable String username, Principal principal) {
        return ResponseEntity.ok(followService.getFollowInfo(viewerEmail(principal), username));
    }

    @GetMapping("/followers")
    public ResponseEntity<Page<FollowUserResponse>> followers(
            @PathVariable String username,
            @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable,
            Principal principal) {
        return ResponseEntity.ok(followService.getFollowers(viewerEmail(principal), username, pageable));
    }

    @GetMapping("/following")
    public ResponseEntity<Page<FollowUserResponse>> following(
            @PathVariable String username,
            @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable,
            Principal principal) {
        return ResponseEntity.ok(followService.getFollowing(viewerEmail(principal), username, pageable));
    }

    // {username} ở đây là người theo dõi mà mình muốn gỡ
    @DeleteMapping("/remove-follower")
    public ResponseEntity<FollowInfoResponse> removeFollower(@PathVariable String username, Principal principal) {
        return ResponseEntity.ok(followService.removeFollower(principal.getName(), username));
    }

    // Principal có thể null với khách chưa đăng nhập (các GET công khai)
    private String viewerEmail(Principal principal) {
        return principal != null ? principal.getName() : null;
    }

    // ---- Mã lỗi rõ ràng thay vì tất cả đều là 400 ----

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(404).body(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleForbidden(IllegalStateException e) {
        return ResponseEntity.status(403).body(e.getMessage());
    }
}