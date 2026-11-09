package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.AdminUserResponse;
import hcmute.edu.vn.backend.dto.AdminUserStatusRequest;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "ACTIVE",
            "LOCKED"
    );

    private final UserRepository userRepository;
    private final PartnerAuditService auditService;

    /**
     * Admin tìm kiếm người dùng.
     */
    @Transactional(readOnly = true)
    public Page<AdminUserResponse> getUsers(
            String role,
            String status,
            String keyword,
            int page,
            int size
    ) {

        validatePagination(page, size);

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        return userRepository.searchUsers(
                        normalizeOptional(role),
                        normalizeOptional(status),
                        trimToNull(keyword),
                        pageable
                )
                .map(AdminUserResponse::from);
    }

    /**
     * Admin khóa hoặc mở khóa tài khoản.
     */
    @Transactional
    public AdminUserResponse updateStatus(
            Long userId,
            AdminUserStatusRequest request,
            String adminEmail
    ) {

        User admin = requireAdmin(adminEmail);

        if (request == null
                || request.status() == null
                || request.status().isBlank()) {

            throw new IllegalArgumentException(
                    "Trạng thái tài khoản không được để trống."
            );
        }

        String newStatus = request.status()
                .trim()
                .toUpperCase(Locale.ROOT);

        if (!ALLOWED_STATUSES.contains(newStatus)) {
            throw new IllegalArgumentException(
                    "Trạng thái chỉ được là ACTIVE hoặc LOCKED."
            );
        }

        User target = userRepository.findById(userId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy người dùng."
                        )
                );

        if (target.getId().equals(admin.getId())
                && "LOCKED".equals(newStatus)) {

            throw new IllegalStateException(
                    "Bạn không thể tự khóa tài khoản Admin đang sử dụng."
            );
        }

        if ("ROLE_ADMIN".equalsIgnoreCase(target.getRole())) {
            throw new IllegalStateException(
                    "Không được thay đổi trạng thái tài khoản Admin "
                            + "trong phiên bản này."
            );
        }

        String oldStatus = target.getStatus();

        if (newStatus.equalsIgnoreCase(oldStatus)) {
            return AdminUserResponse.from(target);
        }

        target.setStatus(newStatus);

        User saved = userRepository.save(target);

        auditService.log(
                adminEmail,
                null,
                "ADMIN_UPDATE_USER_STATUS",
                "USER",
                userId,
                oldStatus,
                newStatus
        );

        return AdminUserResponse.from(saved);
    }

    private User requireAdmin(String email) {

        User admin = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy tài khoản Admin."
                        )
                );

        if (!"ACTIVE".equalsIgnoreCase(admin.getStatus())
                || !"ROLE_ADMIN".equalsIgnoreCase(admin.getRole())) {

            throw new IllegalStateException(
                    "Bạn không có quyền thực hiện thao tác này."
            );
        }

        return admin;
    }

    private void validatePagination(int page, int size) {

        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "Tham số phân trang không hợp lệ."
            );
        }
    }

    private String normalizeOptional(String value) {

        return value == null || value.isBlank()
                ? null
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {

        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}