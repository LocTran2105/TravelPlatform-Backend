package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.InteractionRequest;
import hcmute.edu.vn.backend.service.SocialInteractionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/social")
public class SocialInteractionController {

    @Autowired
    private SocialInteractionService socialService;

    // API: Like / Unlike
    @PostMapping("/likes")
    public ResponseEntity<?> toggleLike(@RequestBody InteractionRequest request, Principal principal) {
        try {
            return ResponseEntity.ok(socialService.toggleLike(request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API: Theo dõi / Hủy theo dõi người dùng
    @PostMapping("/follows/{followedId}")
    public ResponseEntity<?> toggleFollow(@PathVariable Long followedId, Principal principal) {
        try {
            return ResponseEntity.ok(socialService.toggleFollow(followedId, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API: Ghi nhận lượt chia sẻ
    @PostMapping("/shares")
    public ResponseEntity<?> recordShare(@RequestBody InteractionRequest request, Principal principal) {
        try {
            return ResponseEntity.ok(socialService.recordShare(request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }


}