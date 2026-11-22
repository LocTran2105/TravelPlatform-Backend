package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.CommentRequest;
import hcmute.edu.vn.backend.service.CommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    // 1. Lấy danh sách bình luận (2 cấp). Khách chưa đăng nhập vẫn xem được
    //    bình luận của bài PUBLIC; bài không xem được trả 404.
    @GetMapping("/{postId}/comments")
    public ResponseEntity<?> getComments(@PathVariable Long postId, Principal principal) {
        try {
            String email = principal != null ? principal.getName() : null;
            return ResponseEntity.ok(commentService.getCommentsByPost(postId, email));
        } catch (Exception e) {
            return error(e, "Lỗi tải bình luận: ");
        }
    }

    // 2. Viết bình luận gốc HOẶC trả lời (nếu có parentId)
    @PostMapping("/{postId}/comments")
    public ResponseEntity<?> addComment(
            @PathVariable Long postId,
            @RequestBody CommentRequest request,
            Principal principal) {
        try {
            return ResponseEntity.ok(commentService.addComment(postId, request, principal.getName()));
        } catch (Exception e) {
            return error(e, "Lỗi gửi bình luận: ");
        }
    }

    // 3. Chỉnh sửa bình luận (chỉ chủ bình luận)
    @PutMapping("/comments/{commentId}")
    public ResponseEntity<?> updateComment(
            @PathVariable Long commentId,
            @RequestBody CommentRequest request,
            Principal principal) {
        try {
            return ResponseEntity.ok(commentService.updateComment(commentId, request, principal.getName()));
        } catch (Exception e) {
            return error(e, "Lỗi sửa bình luận: ");
        }
    }

    // 4. Xóa bình luận (chủ bình luận hoặc chủ bài viết)
    @DeleteMapping("/comments/{commentId}")
    public ResponseEntity<?> deleteComment(
            @PathVariable Long commentId,
            Principal principal) {
        try {
            commentService.deleteComment(commentId, principal.getName());
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return error(e, "Lỗi xóa bình luận: ");
        }
    }

    /** Trả đúng mã HTTP thay vì gom hết vào 400 / 500. */
    private ResponseEntity<String> error(Exception e, String prefix) {
        if (e instanceof NoSuchElementException) {
            return ResponseEntity.status(404).body(e.getMessage());
        }
        if (e instanceof AccessDeniedException) {
            return ResponseEntity.status(403).body(e.getMessage());
        }
        if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        return ResponseEntity.internalServerError().body(prefix + e.getMessage());
    }
}