package hcmute.edu.vn.backend.controller;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import hcmute.edu.vn.backend.dto.AuthResponse;
import hcmute.edu.vn.backend.dto.GoogleLoginRequest;
import hcmute.edu.vn.backend.dto.LoginRequest;
import hcmute.edu.vn.backend.dto.SignupRequest;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.UserRepository;
import hcmute.edu.vn.backend.security.JwtUtils;
import hcmute.edu.vn.backend.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.Collections;


@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Value("${google.client.id}")
    private String googleClientId;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;
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

    // Nơi tiếp nhận Request từ ReactJS và đẩy qua Service xử lý
    @PostMapping("/google")
    public ResponseEntity<?> authenticateGoogleUser(@RequestBody GoogleLoginRequest request) {
        try {
            return ResponseEntity.ok(authService.authenticateGoogleUser(request.getToken()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi xác thực: " + e.getMessage());
        }
    }
}