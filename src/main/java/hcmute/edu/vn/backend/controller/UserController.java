package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.UpdateProfileRequest;
import hcmute.edu.vn.backend.dto.UserMinimalDTO;
import hcmute.edu.vn.backend.dto.UserProfileDTO;
import hcmute.edu.vn.backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    private String getCurrentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    // 1. Lấy thông tin cá nhân
    @GetMapping("/profile")
    public ResponseEntity<?> getProfile() {
        try {
            String email = getCurrentUserEmail();
            UserProfileDTO profile = userService.getUserProfile(email);
            return ResponseEntity.ok(profile);
        } catch (Exception e) {
            return ResponseEntity.status(404).body(e.getMessage());
        }
    }

    // 2. Cập nhật thông tin cá nhân
    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestBody UpdateProfileRequest request) {
        try {
            String email = getCurrentUserEmail();
            userService.updateProfile(email, request);
            return ResponseEntity.ok("Cập nhật thông tin thành công!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 3. Đổi mật khẩu
    @PutMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> request) {
        try {
            String email = getCurrentUserEmail();
            String oldPassword = request.get("oldPassword");
            String newPassword = request.get("newPassword");

            userService.changePassword(email, oldPassword, newPassword);
            return ResponseEntity.ok("Đổi mật khẩu thành công!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    //4. Trang profile
    @GetMapping("/{username}")
    public ResponseEntity<?> getPublicProfile(@PathVariable String username, java.security.Principal principal) {
        try {
            // Lấy email của người đang thao tác (nếu có đăng nhập)
            String currentUserEmail = (principal != null) ? principal.getName() : null;

            // Truyền email xuống Service để check Follow
            UserMinimalDTO publicProfile = userService.getPublicProfile(username, currentUserEmail);
            return ResponseEntity.ok(publicProfile);
        } catch (Exception e) {
            return ResponseEntity.status(404).body(e.getMessage());
        }
    }
}