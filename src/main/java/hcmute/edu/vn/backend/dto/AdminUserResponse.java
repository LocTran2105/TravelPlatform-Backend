package hcmute.edu.vn.backend.dto;

import hcmute.edu.vn.backend.entity.User;

import java.time.LocalDateTime;

public record AdminUserResponse(
        Long id,
        String email,
        String phoneNumber,
        String fullName,
        String avatar,
        String role,
        String status,
        LocalDateTime createdAt
) {

    public static AdminUserResponse from(User user) {

        return new AdminUserResponse(
                user.getId(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getFullName(),
                user.getAvatar(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }
}