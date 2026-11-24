package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.UpdateProfileRequest;
import hcmute.edu.vn.backend.dto.UserMinimalDTO;
import hcmute.edu.vn.backend.dto.UserProfileDTO;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.FollowRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private FollowRepository followRepository;

    // 1. Lấy thông tin User
    public UserProfileDTO getUserProfile(String email) throws Exception {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng"));

        return UserProfileDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .username(user.getUsername())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .build();
    }

    // 2. Cập nhật thông tin User
    @Transactional
    public void updateProfile(String email, UpdateProfileRequest request) throws Exception {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng"));

        String fullName = request.getFullName() == null ? "" : request.getFullName().trim();
        if (fullName.isEmpty()) throw new Exception("Họ và tên không được để trống!");
        if (fullName.length() > 150) throw new Exception("Họ và tên quá dài!");

        // Rỗng -> null (cột phone_number là UNIQUE, không cho nhiều giá trị '')
        String phone = request.getPhoneNumber() == null ? null : request.getPhoneNumber().trim();
        if (phone != null && phone.isEmpty()) phone = null;
        if (phone != null) {
            if (phone.length() > 20) throw new Exception("Số điện thoại không hợp lệ!");
            Optional<User> holder = userRepository.findByPhoneNumber(phone);
            if (holder.isPresent() && !holder.get().getId().equals(user.getId())) {
                throw new Exception("Số điện thoại đã được sử dụng!");
            }
        }

        String newUsername = request.getUsername() == null ? "" : request.getUsername().trim().toLowerCase();
        if (!newUsername.isEmpty() && !newUsername.equals(user.getUsername())) {
            if (!newUsername.matches("^[a-z0-9][a-z0-9._-]{1,8}[a-z0-9]$")) {
                throw new Exception("Username dài 3-10 ký tự, chỉ gồm chữ thường, số và . _ - ; phải bắt đầu và kết thúc bằng chữ hoặc số.");
            }
            Optional<User> holder = userRepository.findByUsername(newUsername);
            if (holder.isPresent() && !holder.get().getId().equals(user.getId())) {
                throw new Exception("Username này đã có người sử dụng!");
            }
            user.setUsername(newUsername);
        }

        user.setFullName(fullName);
        user.setPhoneNumber(phone);
        userRepository.save(user);
    }

    // 3. Đổi mật khẩu
    @Transactional
    public void changePassword(String email, String oldPassword, String newPassword) throws Exception {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng"));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new Exception("Mật khẩu hiện tại không chính xác!");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    //4. Trang hồ sơ cá nhân, hiển thị thông tin
    public UserMinimalDTO getPublicProfile(String username, String currentUserEmail) throws Exception {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng"));

        boolean isFollowedByMe = false;
        boolean isFollowingMe = false;

        // Nếu có người đang đăng nhập vào xem trang, kiểm tra trạng thái Follow 2 chiều
        if (currentUserEmail != null) {
            java.util.Optional<User> currentUserOpt = userRepository.findByEmail(currentUserEmail);
            if (currentUserOpt.isPresent()) {
                User currentUser = currentUserOpt.get();
                if (!currentUser.getId().equals(user.getId())) {
                    isFollowedByMe = followRepository.existsByFollowerAndFollowed(currentUser, user); // Mình có follow họ không?
                    isFollowingMe = followRepository.existsByFollowerAndFollowed(user, currentUser); // Họ có follow mình không?
                }
            }
        }

        return hcmute.edu.vn.backend.dto.UserMinimalDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .avatar(user.getAvatar() != null ? user.getAvatar() : "")
                .FollowedByMe(isFollowedByMe)
                .FollowingMe(isFollowingMe)
                .build();
    }
}