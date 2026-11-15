package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.UpdatePostRequest;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.service.PostService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /**
     * Đăng bài mới, hỗ trợ nhiều ảnh/video.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> createPost(
            @RequestParam(value = "caption", required = false)
            String caption,

            @RequestParam(value = "locationName", required = false)
            String locationName,

            @RequestParam(value = "latitude", required = false)
            Double latitude,

            @RequestParam(value = "longitude", required = false)
            Double longitude,

            @RequestParam(value = "rating", required = false)
            Integer rating,

            @RequestParam(value = "files", required = false)
            List<MultipartFile> files,

            @RequestParam(value = "visibility", required = false, defaultValue = "PUBLIC")
            String visibility,

            Principal principal
    ) {

        try {

            Post post = postService.createPost(
                    principal.getName(),
                    caption,
                    locationName,
                    latitude,
                    longitude,
                    rating,
                    visibility,
                    files
            );

            // Trả id + quyền ĐÃ LƯU thay vì cả entity Post (entity kéo theo
            // email, số điện thoại... của tác giả). Có "visibility" ở đây
            // thì xem tab Network là biết server đã lưu quyền gì.
            return ResponseEntity.ok(Map.of(
                    "id", post.getId(),
                    "visibility", post.getVisibility()
            ));

        } catch (IllegalArgumentException exception) {

            return ResponseEntity.badRequest()
                    .body("Dữ liệu không hợp lệ: "
                            + exception.getMessage());

        } catch (IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());

        } catch (Exception exception) {

            return ResponseEntity.internalServerError()
                    .body("Lỗi khi đăng bài: "
                            + exception.getMessage());
        }
    }

    /**
     * Chỉnh sửa bài (nội dung + quyền hiển thị). Chỉ chủ bài.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updatePost(
            @PathVariable Long id,
            @RequestBody UpdatePostRequest request,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    postService.updatePost(
                            principal.getName(),
                            id,
                            request.getCaption(),
                            request.getVisibility()
                    )
            );
        } catch (Exception exception) {
            return errorResponse(exception, "Lỗi khi chỉnh sửa bài viết: ");
        }
    }

    /**
     * Xóa mềm bài viết (status = DELETED). Chỉ chủ bài.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePost(
            @PathVariable Long id,
            Principal principal
    ) {
        try {
            postService.deletePost(principal.getName(), id);
            return ResponseEntity.noContent().build();
        } catch (Exception exception) {
            return errorResponse(exception, "Lỗi khi xóa bài viết: ");
        }
    }

    /**
     * Feed cộng đồng.
     *
     * Chỉ trả bài đã duyệt, chưa xóa và người xem được phép thấy.
     */
    @GetMapping
    public ResponseEntity<?> getAllPosts(Principal principal) {
        try {
            String currentUserEmail = (principal != null) ? principal.getName() : null;
            return ResponseEntity.ok(
                    postService.getAllPosts(currentUserEmail)
            );
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body("Lỗi tải bài viết: " + exception.getMessage());
        }
    }

    @GetMapping("/user/{username}")
    public ResponseEntity<?> getUserPosts(
            @PathVariable String username,
            Principal principal
    ) {
        try {
            String currentUserEmail = (principal != null) ? principal.getName() : null;
            return ResponseEntity.ok(postService.getUserPosts(username, currentUserEmail));
        } catch (IllegalArgumentException e) {
            // "Không tìm thấy người dùng." → 404 thay vì 400
            return ResponseEntity.status(404).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi tải bài viết: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private ResponseEntity<String> errorResponse(Exception exception, String prefix) {
        if (exception instanceof NoSuchElementException) {
            return ResponseEntity.status(404).body(exception.getMessage());
        }
        if (exception instanceof AccessDeniedException) {
            return ResponseEntity.status(403).body(exception.getMessage());
        }
        if (exception instanceof IllegalArgumentException
                || exception instanceof IllegalStateException) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        }
        return ResponseEntity.internalServerError().body(prefix + exception.getMessage());
    }
}