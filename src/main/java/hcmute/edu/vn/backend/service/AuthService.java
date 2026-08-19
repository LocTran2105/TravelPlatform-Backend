package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.AuthResponse;
import hcmute.edu.vn.backend.dto.LoginRequest;
import hcmute.edu.vn.backend.dto.SignupRequest;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.UserRepository;
import hcmute.edu.vn.backend.security.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service // Đánh dấu đây là tầng Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils;

    // Logic Đăng ký
    public void registerUser(SignupRequest signUpRequest) throws Exception {
        if (userRepository.existsByEmail(signUpRequest.getEmail())) {
            throw new Exception("Email đã được sử dụng!");
        }
        if (userRepository.existsByPhoneNumber(signUpRequest.getPhoneNumber())) {
            throw new Exception("Số điện thoại đã được sử dụng!");
        }

        User user = new User();
        user.setEmail(signUpRequest.getEmail());
        user.setPhoneNumber(signUpRequest.getPhoneNumber());
        user.setFullName(signUpRequest.getFullName());
        user.setPasswordHash(passwordEncoder.encode(signUpRequest.getPassword()));
        user.setRole("ROLE_TRAVELLER");

        userRepository.save(user);
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

        if ("LOCKED".equals(user.getStatus())) {
            throw new Exception("Tài khoản của bạn đã bị khóa!");
        }

        String jwtToken = jwtUtils.generateTokenFromEmail(user.getEmail());

        return new AuthResponse(
                jwtToken,
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole()
        );
    }
}