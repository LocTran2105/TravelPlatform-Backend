package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.AdminReviewRequest;
import hcmute.edu.vn.backend.service.AdminPostService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/admin/posts")
@RequiredArgsConstructor
public class AdminPostController {

    private final AdminPostService postService;

    /**
     * Danh sách bài đăng theo trạng thái.
     */
    @GetMapping
    public ResponseEntity<?> getPosts(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {

        try {

            return ResponseEntity.ok(
                    postService.getPosts(
                            status,
                            page,
                            size
                    )
            );

        } catch (IllegalArgumentException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Chi tiết bài đăng.
     */
    @GetMapping("/{postId}")
    public ResponseEntity<?> getPost(
            @PathVariable Long postId
    ) {

        try {

            return ResponseEntity.ok(
                    postService.getPost(postId)
            );

        } catch (IllegalArgumentException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Duyệt bài đăng.
     */
    @PutMapping("/{postId}/approve")
    public ResponseEntity<?> approve(
            @PathVariable Long postId,
            @RequestBody(required = false) AdminReviewRequest request,
            Principal principal
    ) {

        try {

            return ResponseEntity.ok(
                    postService.approve(
                            postId,
                            request == null ? null : request.note(),
                            principal.getName()
                    )
            );

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Từ chối bài đăng.
     */
    @PutMapping("/{postId}/reject")
    public ResponseEntity<?> reject(
            @PathVariable Long postId,
            @RequestBody AdminReviewRequest request,
            Principal principal
    ) {

        try {

            return ResponseEntity.ok(
                    postService.reject(
                            postId,
                            request == null ? null : request.note(),
                            principal.getName()
                    )
            );

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Ẩn bài đăng.
     */
    @PutMapping("/{postId}/hide")
    public ResponseEntity<?> hide(
            @PathVariable Long postId,
            @RequestBody AdminReviewRequest request,
            Principal principal
    ) {

        try {

            return ResponseEntity.ok(
                    postService.hide(
                            postId,
                            request == null ? null : request.note(),
                            principal.getName()
                    )
            );

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Khôi phục bài đăng đã ẩn.
     */
    @PutMapping("/{postId}/restore")
    public ResponseEntity<?> restore(
            @PathVariable Long postId,
            @RequestBody(required = false) AdminReviewRequest request,
            Principal principal
    ) {

        try {

            return ResponseEntity.ok(
                    postService.restore(
                            postId,
                            request == null ? null : request.note(),
                            principal.getName()
                    )
            );

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }
}