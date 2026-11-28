package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.PostTripDtos;
import hcmute.edu.vn.backend.dto.UpdatePostRequest;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.service.FeedService;
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
    private final FeedService feedService;

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

            // Lịch trình gắn vào bài (không bắt buộc): phải là chuyến đi của chính người đăng.
            @RequestParam(value = "tripId", required = false)
            Long tripId,

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
                    tripId,
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
     *
     *  - sort=ranked : tab "Dành cho bạn" (chấm điểm theo độ mới, tương tác, quan hệ theo dõi).
     *  - sort=latest : mới nhất trước (mặc định khi có size / cursor mà không có sort).
     *  - size        : số bài mỗi trang (mặc định 10, tối đa 30).
     *  - cursor      : lấy nguyên văn nextCursor của trang trước (không có = trang đầu).
     *  - before      : (cũ) id bài cuối của trang trước, chỉ dùng cho sort=latest.
     *
     *  Trả { items, hasMore, nextCursor }.
     *  Không có tham số nào: giữ hành vi cũ, trả mảng toàn bộ (TripDetail.jsx đang gọi kiểu này).
     */
    @GetMapping
    public ResponseEntity<?> getAllPosts(
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "before", required = false) Long before,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "sort", required = false) String sort,
            Principal principal
    ) {
        try {
            String currentUserEmail = (principal != null) ? principal.getName() : null;

            boolean noParams = size == null && before == null
                    && (cursor == null || cursor.isBlank()) && (sort == null || sort.isBlank());
            if (noParams) {
                return ResponseEntity.ok(postService.getAllPosts(currentUserEmail));
            }

            if ("ranked".equalsIgnoreCase(sort)) {
                return ResponseEntity.ok(feedService.getRankedFeedPage(currentUserEmail, cursor, size));
            }
            if (sort != null && !sort.isBlank() && !"latest".equalsIgnoreCase(sort)) {
                return ResponseEntity.badRequest().body("sort chỉ nhận ranked hoặc latest.");
            }

            Long latestBefore = before;
            if (latestBefore == null && cursor != null && !cursor.isBlank()) {
                try {
                    latestBefore = Long.parseLong(cursor.trim());
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest().body("Con trỏ phân trang không hợp lệ.");
                }
            }
            return ResponseEntity.ok(postService.getFeedPage(currentUserEmail, latestBefore, size));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(exception.getMessage());
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body("Lỗi tải bài viết: " + exception.getMessage());
        }
    }

    /**
     * Lịch trình (chỉ đọc, từ bản chụp) trong bài viết. Ai xem được bài thì xem được, kể cả khách.
     * 404: không xem được bài / bài không có lịch trình.
     */
    @GetMapping("/{id}/trip")
    public ResponseEntity<?> getPostItinerary(@PathVariable Long id, Principal principal) {
        try {
            String viewerEmail = (principal != null) ? principal.getName() : null;
            return ResponseEntity.ok(postService.getPostItinerary(id, viewerEmail));
        } catch (Exception exception) {
            return errorResponse(exception, "Lỗi tải lịch trình: ");
        }
    }

    /**
     * Sao chép lịch trình trong bài thành chuyến đi mới (Riêng tư) của người đang đăng nhập.
     * Body (không bắt buộc): { "title": "...", "startDate": "2026-12-20" }.
     */
    @PostMapping("/{id}/copy-trip")
    public ResponseEntity<?> copyTripFromPost(
            @PathVariable Long id,
            @RequestBody(required = false) PostTripDtos.CopyTripRequest request,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(postService.copyTripFromPost(principal.getName(), id, request));
        } catch (Exception exception) {
            return errorResponse(exception, "Lỗi khi sao chép lịch trình: ");
        }
    }

    /** Tác giả cập nhật lịch trình trong bài theo chuyến đi gốc hiện tại. Trả bài viết đã cập nhật. */
    @PostMapping("/{id}/trip/refresh")
    public ResponseEntity<?> refreshPostTrip(@PathVariable Long id, Principal principal) {
        try {
            return ResponseEntity.ok(postService.refreshPostTrip(principal.getName(), id));
        } catch (Exception exception) {
            return errorResponse(exception, "Lỗi khi cập nhật lịch trình: ");
        }
    }

    /** Tác giả gỡ lịch trình khỏi bài. Trả bài viết đã cập nhật. */
    @DeleteMapping("/{id}/trip")
    public ResponseEntity<?> removePostTrip(@PathVariable Long id, Principal principal) {
        try {
            return ResponseEntity.ok(postService.removePostTrip(principal.getName(), id));
        } catch (Exception exception) {
            return errorResponse(exception, "Lỗi khi gỡ lịch trình: ");
        }
    }

    /** Bài của một người; cũng phân trang khi có size / before (giống feed). */
    @GetMapping("/user/{username}")
    public ResponseEntity<?> getUserPosts(
            @PathVariable String username,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "before", required = false) Long before,
            Principal principal
    ) {
        try {
            String currentUserEmail = (principal != null) ? principal.getName() : null;
            if (size == null && before == null) {
                return ResponseEntity.ok(postService.getUserPosts(username, currentUserEmail));
            }
            return ResponseEntity.ok(postService.getUserPostsPage(username, currentUserEmail, before, size));
        } catch (IllegalArgumentException e) {
            // "Không tìm thấy người dùng." -> 404 thay vì 400
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