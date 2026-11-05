package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Hàm tiện ích lấy email từ JWT hiện tại
    private String getCurrentUserEmail() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    // 0. Lấy thông tin cá nhân (dùng cho xác thực ngầm khi mở app)
    @GetMapping("/profile")
    public ResponseEntity<?> getProfile() {
        String email = getCurrentUserEmail();
        return userRepository.findByEmail(email)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(Map.of(
                        "email", user.getEmail(),
                        "fullName", Objects.toString(user.getFullName(), ""),
                        "phoneNumber", Objects.toString(user.getPhoneNumber(), ""),
                        "role", Objects.toString(user.getRole(), "")
                )))
                .orElseGet(() -> ResponseEntity.status(404).body("Không tìm thấy người dùng"));
    }

    // 1. Cập nhật thông tin cá nhân
    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(@RequestBody Map<String, String> request) {
        try {
            String email = getCurrentUserEmail();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new Exception("Không tìm thấy người dùng"));

            user.setFullName(request.get("fullName"));
            // Bạn có thể thêm check trùng lặp số điện thoại ở đây nếu cần
            user.setPhoneNumber(request.get("phoneNumber"));

            userRepository.save(user);
            return ResponseEntity.ok("Cập nhật thông tin thành công!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // 2. Đổi mật khẩu
    @PutMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> request) {
        try {
            String email = getCurrentUserEmail();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new Exception("Không tìm thấy người dùng"));

            String oldPassword = request.get("oldPassword");
            String newPassword = request.get("newPassword");

            if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
                return ResponseEntity.badRequest().body("Mật khẩu hiện tại không chính xác!");
            }

            user.setPasswordHash(passwordEncoder.encode(newPassword));
            userRepository.save(user);

            return ResponseEntity.ok("Đổi mật khẩu thành công!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}