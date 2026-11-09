package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.AdminUserStatusRequest;
import hcmute.edu.vn.backend.service.AdminUserService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService userService;

    /**
     * Danh sách người dùng.
     *
     * Hỗ trợ lọc role, status và tìm kiếm.
     */
    @GetMapping
    public ResponseEntity<?> getUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        try {

            return ResponseEntity.ok(
                    userService.getUsers(
                            role,
                            status,
                            keyword,
                            page,
                            size
                    )
            );

        } catch (IllegalArgumentException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Khóa hoặc mở khóa tài khoản.
     */
    @PatchMapping("/{userId}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long userId,
            @RequestBody AdminUserStatusRequest request,
            Principal principal
    ) {
        try {

            return ResponseEntity.ok(
                    userService.updateStatus(
                            userId,
                            request,
                            principal.getName()
                    )
            );

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }
}