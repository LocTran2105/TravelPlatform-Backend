package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.AdminPostResponse;
import hcmute.edu.vn.backend.entity.Image;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.ImageRepository;
import hcmute.edu.vn.backend.repository.PostRepository;
import hcmute.edu.vn.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminPostService {

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "PENDING",
            "APPROVED",
            "REJECTED",
            "HIDDEN"
    );

    private final PostRepository postRepository;

    private final ImageRepository imageRepository;

    private final UserRepository userRepository;

    private final PartnerAuditService auditService;

    /**
     * Admin lấy danh sách bài đăng theo trạng thái.
     */
    @Transactional(readOnly = true)
    public Page<AdminPostResponse> getPosts(
            String status,
            int page,
            int size
    ) {

        validatePagination(page, size);

        String normalizedStatus = normalizeStatus(status);

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Direction.DESC,
                        "createdAt"
                )
        );

        return postRepository
                .findByModerationStatusOrderByCreatedAtDesc(
                        normalizedStatus,
                        pageable
                )
                .map(this::toResponse);
    }

    /**
     * Admin xem chi tiết một bài đăng.
     */
    @Transactional(readOnly = true)
    public AdminPostResponse getPost(Long postId) {

        Post post = postRepository.findById(postId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy bài đăng."
                        )
                );

        return toResponse(post);
    }

    /**
     * Duyệt bài đăng.
     */
    @Transactional
    public AdminPostResponse approve(
            Long postId,
            String note,
            String adminEmail
    ) {

        User admin = requireAdmin(adminEmail);

        Post post = findForModeration(postId);

        if (!"PENDING".equalsIgnoreCase(
                post.getModerationStatus()
        )) {

            throw new IllegalStateException(
                    "Chỉ bài đăng đang chờ duyệt mới được chấp nhận."
            );
        }

        String oldStatus = post.getModerationStatus();

        post.setModerationStatus("APPROVED");
        post.setModeratedBy(admin);
        post.setModeratedAt(LocalDateTime.now());
        post.setModerationNote(trimToNull(note));

        Post saved = postRepository.save(post);

        auditService.log(
                adminEmail,
                null,
                "APPROVE_POST",
                "POST",
                postId,
                oldStatus,
                "APPROVED"
        );

        return toResponse(saved);
    }

    /**
     * Từ chối bài đăng.
     */
    @Transactional
    public AdminPostResponse reject(
            Long postId,
            String note,
            String adminEmail
    ) {

        User admin = requireAdmin(adminEmail);

        Post post = findForModeration(postId);

        if (!"PENDING".equalsIgnoreCase(
                post.getModerationStatus()
        )) {

            throw new IllegalStateException(
                    "Chỉ bài đăng đang chờ duyệt mới được từ chối."
            );
        }

        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException(
                    "Vui lòng nhập lý do từ chối bài đăng."
            );
        }

        String oldStatus = post.getModerationStatus();

        post.setModerationStatus("REJECTED");
        post.setModeratedBy(admin);
        post.setModeratedAt(LocalDateTime.now());
        post.setModerationNote(note.trim());

        Post saved = postRepository.save(post);

        auditService.log(
                adminEmail,
                null,
                "REJECT_POST",
                "POST",
                postId,
                oldStatus,
                "REJECTED"
        );

        return toResponse(saved);
    }

    /**
     * Ẩn bài đăng đã được duyệt.
     */
    @Transactional
    public AdminPostResponse hide(
            Long postId,
            String note,
            String adminEmail
    ) {

        User admin = requireAdmin(adminEmail);

        Post post = findForModeration(postId);

        if (!"APPROVED".equalsIgnoreCase(
                post.getModerationStatus()
        )) {

            throw new IllegalStateException(
                    "Chỉ bài đăng đã duyệt mới có thể bị ẩn."
            );
        }

        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException(
                    "Vui lòng nhập lý do ẩn bài đăng."
            );
        }

        String oldStatus = post.getModerationStatus();

        post.setModerationStatus("HIDDEN");
        post.setModeratedBy(admin);
        post.setModeratedAt(LocalDateTime.now());
        post.setModerationNote(note.trim());

        Post saved = postRepository.save(post);

        auditService.log(
                adminEmail,
                null,
                "HIDE_POST",
                "POST",
                postId,
                oldStatus,
                "HIDDEN"
        );

        return toResponse(saved);
    }

    /**
     * Khôi phục bài đăng bị ẩn.
     */
    @Transactional
    public AdminPostResponse restore(
            Long postId,
            String note,
            String adminEmail
    ) {

        User admin = requireAdmin(adminEmail);

        Post post = findForModeration(postId);

        if (!"HIDDEN".equalsIgnoreCase(
                post.getModerationStatus()
        )) {

            throw new IllegalStateException(
                    "Chỉ bài đăng đang ẩn mới được khôi phục."
            );
        }

        String oldStatus = post.getModerationStatus();

        post.setModerationStatus("APPROVED");
        post.setModeratedBy(admin);
        post.setModeratedAt(LocalDateTime.now());
        post.setModerationNote(trimToNull(note));

        Post saved = postRepository.save(post);

        auditService.log(
                adminEmail,
                null,
                "RESTORE_POST",
                "POST",
                postId,
                oldStatus,
                "APPROVED"
        );

        return toResponse(saved);
    }

    /**
     * Tìm bài đăng và khóa bản ghi.
     */
    private Post findForModeration(Long postId) {

        return postRepository.findByIdForModeration(postId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy bài đăng."
                        )
                );
    }

    /**
     * Xác nhận người thao tác là Admin.
     */
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

    /**
     * Chuyển Entity thành DTO.
     */
    private AdminPostResponse toResponse(Post post) {

        List<Image> media =
                imageRepository.findByEntityTypeAndEntityId(
                        "POST",
                        post.getId()
                );

        return AdminPostResponse.from(post, media);
    }

    private String normalizeStatus(String status) {

        String normalized = status == null || status.isBlank()
                ? "PENDING"
                : status.trim().toUpperCase(Locale.ROOT);

        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Trạng thái bài đăng không hợp lệ."
            );
        }

        return normalized;
    }

    private String trimToNull(String value) {

        return value == null || value.isBlank()
                ? null
                : value.trim();
    }

    private void validatePagination(int page, int size) {

        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "Tham số phân trang không hợp lệ."
            );
        }
    }
}