package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.LoginRequest;
import hcmute.edu.vn.backend.dto.SignupRequest;
import hcmute.edu.vn.backend.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService; // Chỉ tiêm Service vào Controller

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody SignupRequest signUpRequest) {
        try {
            authService.registerUser(signUpRequest);
            return ResponseEntity.ok("Đăng ký tài khoản thành công!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody LoginRequest loginRequest) {
        try {
            // Service trả về đối tượng AuthResponse, Controller chỉ việc ném nó vào ResponseEntity
            return ResponseEntity.ok(authService.authenticateUser(loginRequest));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }
}