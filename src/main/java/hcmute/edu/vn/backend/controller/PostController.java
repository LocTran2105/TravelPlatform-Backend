package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.service.PostService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.util.List;

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
                    files
            );

            return ResponseEntity.ok(post);

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
     * Feed công khai.
     *
     * Chỉ trả bài APPROVED.
     */
    @GetMapping
    public ResponseEntity<?> getAllPosts() {

        try {

            return ResponseEntity.ok(
                    postService.getAllPosts()
            );

        } catch (Exception exception) {

            return ResponseEntity.internalServerError()
                    .body("Lỗi tải bài viết: "
                            + exception.getMessage());
        }
    }
}