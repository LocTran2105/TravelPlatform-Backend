package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.service.PostService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    @Autowired
    private PostService postService;

    // API Đăng bài mới (Hỗ trợ upload nhiều ảnh/video)
    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<?> createPost(
            @RequestParam(value = "caption", required = false) String caption,
            @RequestParam(value = "locationName", required = false) String locationName,
            @RequestParam(value = "latitude", required = false) Double latitude,
            @RequestParam(value = "longitude", required = false) Double longitude,
            @RequestParam(value = "rating", required = false) Integer rating,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            Principal principal) {
        try {
            Post post = postService.createPost(
                    principal.getName(), caption, locationName, latitude, longitude, rating, files
            );
            return ResponseEntity.ok(post);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi khi đăng bài: " + e.getMessage());
        }
    }

    // API Lấy danh sách bài viết (Hiển thị ở trang Cộng đồng hoặc Explore)
    @GetMapping
    public ResponseEntity<?> getAllPosts() {
        try {
            return ResponseEntity.ok(postService.getAllPosts());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi tải bài viết: " + e.getMessage());
        }
    }
}