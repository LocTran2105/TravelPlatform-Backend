package hcmute.edu.vn.backend.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import hcmute.edu.vn.backend.dto.AuthResponse;
import hcmute.edu.vn.backend.dto.LoginRequest;
import hcmute.edu.vn.backend.dto.SignupRequest;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.UserRepository;
import hcmute.edu.vn.backend.security.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

@Service // Đánh dấu đây là tầng Service
public class AuthService {

    private static final int SAVE_ATTEMPTS = 5;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    @Value("${google.client.id}")
    private String googleClientId;

    @Autowired
    private JavaMailSender mailSender;

    // Logic Đăng ký
    public void registerUser(SignupRequest signUpRequest) throws Exception {
        if (userRepository.existsByEmail(signUpRequest.getEmail())) {
            throw new Exception("Email đã được sử dụng!");
        }
        if (signUpRequest.getPhoneNumber() != null && userRepository.existsByPhoneNumber(signUpRequest.getPhoneNumber())) {
            throw new Exception("Số điện thoại đã được sử dụng!");
        }

        User user = new User();
        user.setEmail(signUpRequest.getEmail());
        user.setPhoneNumber(signUpRequest.getPhoneNumber());
        user.setFullName(signUpRequest.getFullName());
        user.setPasswordHash(passwordEncoder.encode(signUpRequest.getPassword()));
        user.setRole("ROLE_TRAVELLER");
        user.setStatus("ACTIVE");

        // Username tự tạo từ email (đăng ký thường và đăng nhập Google dùng chung một hàm)
        saveWithUniqueUsername(user);
    }

    // Logic Đăng nhập
    public AuthResponse authenticateUser(LoginRequest loginRequest) throws Exception {
        Optional<User> userOptional = userRepository.findByEmail(loginRequest.getEmail());

        if (userOptional.isEmpty()) {
            throw new Exception("Không tìm thấy tài khoản với email này!");
        }

        User user = userOptional.get();

        if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPasswordHash())) {
            throw new Exception("Mật khẩu không chính xác!");
        }

        assertAccountUsable(user);

        String jwtToken = jwtUtils.generateJwtTokenFromEmail(user.getEmail());

        return new AuthResponse(
                jwtToken,
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole()
        );
    }

    // Logic Đăng nhập bằng Google
    public AuthResponse authenticateGoogleUser(String token) throws Exception {
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), new GsonFactory())
                .setAudience(Collections.singletonList(googleClientId))
                .build();

        GoogleIdToken idToken = verifier.verify(token);
        if (idToken == null) {
            throw new Exception("Token Google không hợp lệ!");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        String email = payload.getEmail();
        String name = (String) payload.get("name");

        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            user = new User();
            user.setEmail(email);
            // full_name NOT NULL: Google không trả tên thì lấy phần trước @ của email
            user.setFullName(name != null && !name.isBlank() ? name.trim() : email.split("@")[0]);
            user.setRole("ROLE_TRAVELLER");
            user.setStatus("ACTIVE");
            user.setPasswordHash(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
            // Xử lý tạm số điện thoại để không bị lỗi null
            user.setPhoneNumber(String.valueOf(System.currentTimeMillis()).substring(3));
            // username NOT NULL: lấy từ email Google (trước đây thiếu nên không tạo được tài khoản mới)
            user = saveWithUniqueUsername(user);
        } else {
            // Tài khoản đã có: Google cũng phải tuân thủ trạng thái khóa / ngừng hoạt động
            assertAccountUsable(user);
        }

        String jwt = jwtUtils.generateJwtTokenFromEmail(user.getEmail());

        // Trả về đủ 5 tham số (có thêm fullName) để không bị lỗi Expected 5 arguments
        return new AuthResponse(jwt, user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }

    // 1. Logic Gửi Email Quên Mật Khẩu
    public void processForgotPassword(String email) throws Exception {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            throw new Exception("Không tìm thấy tài khoản với email này!");
        }

        User user = userOpt.get();
        // Tạo token ngẫu nhiên và thời gian hết hạn (VD: 15 phút)
        String token = UUID.randomUUID().toString();
        user.setResetPasswordToken(token);
        user.setTokenExpiryDate(LocalDateTime.now().plusMinutes(15));
        userRepository.save(user);

        // Gửi email
        String resetLink = "http://localhost:5173/reset-password?token=" + token; // Link trỏ về ReactJS
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(user.getEmail());
        message.setSubject("[TravelPlatform] Yêu cầu khôi phục mật khẩu");
        message.setText("Chào " + user.getFullName() + ",\n\n"
                + "Bạn đã yêu cầu đặt lại mật khẩu. Vui lòng click vào link dưới đây để tiến hành:\n"
                + resetLink + "\n\n"
                + "Link này sẽ hết hạn trong 15 phút.\n"
                + "Nếu bạn không yêu cầu, vui lòng bỏ qua email này.");

        mailSender.send(message);
    }

    // 2. Logic Đặt Lại Mật Khẩu
    public void resetPassword(String token, String newPassword) throws Exception {
        Optional<User> userOpt = userRepository.findByResetPasswordToken(token);

        if (userOpt.isEmpty()) {
            throw new Exception("Token không hợp lệ hoặc không tồn tại!");
        }

        User user = userOpt.get();
        if (user.getTokenExpiryDate().isBefore(LocalDateTime.now())) {
            throw new Exception("Token đã hết hạn. Vui lòng yêu cầu lại!");
        }

        // Cập nhật mật khẩu mới và xóa token
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setResetPasswordToken(null);
        user.setTokenExpiryDate(null);
        userRepository.save(user);
    }

    // ------------------------------------------------------------------
    // HỖ TRỢ
    // ------------------------------------------------------------------

    /** Chỉ tài khoản ACTIVE mới được đăng nhập (khớp với AuthTokenFilter). */
    private void assertAccountUsable(User user) throws Exception {
        String status = user.getStatus();
        if ("ACTIVE".equalsIgnoreCase(status)) {
            return;
        }
        if ("LOCKED".equalsIgnoreCase(status)) {
            throw new Exception("Tài khoản của bạn đã bị khóa!");
        }
        if ("DISABLED".equalsIgnoreCase(status)) {
            throw new Exception("Tài khoản của bạn đã ngừng hoạt động!");
        }
        throw new Exception("Tài khoản hiện không hoạt động!");
    }

    /**
     * Lưu user mới kèm username tạo từ email. Nếu hai người đăng ký cùng lúc và trùng username
     * (UNIQUE chặn ở DB) thì chọn lại username khác rồi thử tiếp.
     */
    private User saveWithUniqueUsername(User user) throws Exception {
        for (int attempt = 0; attempt < SAVE_ATTEMPTS; attempt++) {
            String username = UsernameGenerator.unique(user.getEmail(), userRepository::existsByUsername);
            if (username == null) {
                throw new Exception("Không thể tạo tên người dùng, vui lòng thử lại!");
            }
            user.setUsername(username);
            try {
                return userRepository.save(user);
            } catch (DataIntegrityViolationException e) {
                if (userRepository.existsByEmail(user.getEmail())) {
                    throw new Exception("Email đã được sử dụng!");
                }
                // còn lại: trùng username do đăng ký đồng thời -> vòng sau chọn lại
            }
        }
        throw new Exception("Không thể tạo tên người dùng, vui lòng thử lại!");
    }
}
