package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.Alert;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.Report;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.AlertRepository;
import hcmute.edu.vn.backend.repository.PostRepository;
import hcmute.edu.vn.backend.repository.ReportRepository;
import hcmute.edu.vn.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final AlertRepository alertRepository;

    private final ReportRepository reportRepository;

    private final PostRepository postRepository;

    private final UserRepository userRepository;

    private final PartnerAuditService auditService;

    /**
     * Admin duyệt hoặc từ chối cảnh báo bản đồ.
     */
    @Transactional
    public String processAlert(
            Long alertId,
            boolean isApproved
    ) {

        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy cảnh báo."
                        )
                );

        if (!"PENDING".equalsIgnoreCase(alert.getStatus())) {
            throw new IllegalStateException(
                    "Cảnh báo đã được xử lý trước đó."
            );
        }

        alert.setStatus(
                isApproved ? "APPROVED" : "REJECTED"
        );

        alertRepository.save(alert);

        return isApproved
                ? "Đã duyệt cảnh báo thành công!"
                : "Đã từ chối cảnh báo!";
    }

    /**
     * Danh sách báo cáo đang chờ xử lý.
     */
    @Transactional(readOnly = true)
    public Page<Report> getPendingReports(
            int page,
            int size
    ) {

        validatePagination(page, size);

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Direction.DESC,
                        "createdAt"
                )
        );

        return reportRepository.findByStatus(
                "PENDING",
                pageable
        );
    }

    /**
     * Xử lý báo cáo.
     *
     * RESOLVED:
     * Báo cáo hợp lệ.
     *
     * REJECTED:
     * Báo cáo không hợp lệ.
     */
    @Transactional
    public String resolveReport(
            Long reportId,
            String action,
            String adminEmail
    ) {

        User admin = requireAdmin(adminEmail);

        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException(
                    "Vui lòng chọn hành động xử lý."
            );
        }

        String normalizedAction = action.trim()
                .toUpperCase(Locale.ROOT);

        if (!normalizedAction.equals("RESOLVED")
                && !normalizedAction.equals("REJECTED")) {

            throw new IllegalArgumentException(
                    "Hành động chỉ được là RESOLVED hoặc REJECTED."
            );
        }

        Report report = reportRepository
                .findByIdForUpdate(reportId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy báo cáo."
                        )
                );

        if (!"PENDING".equalsIgnoreCase(report.getStatus())) {
            throw new IllegalStateException(
                    "Báo cáo đã được xử lý trước đó."
            );
        }

        // Nếu báo cáo hợp lệ và thuộc bài đăng:
        // chuyển bài viết sang HIDDEN.
        if ("RESOLVED".equals(normalizedAction)
                && "POST".equalsIgnoreCase(
                report.getTargetType()
        )) {

            Post post = postRepository
                    .findByIdForModeration(report.getTargetId())
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Không tìm thấy bài đăng được báo cáo."
                            )
                    );

            if ("APPROVED".equalsIgnoreCase(
                    post.getModerationStatus()
            )) {

                String oldStatus = post.getModerationStatus();

                post.setModerationStatus("HIDDEN");
                post.setModeratedBy(admin);
                post.setModeratedAt(LocalDateTime.now());
                post.setModerationNote(
                        "Bài đăng bị ẩn do báo cáo hợp lệ: "
                                + report.getReason()
                );

                postRepository.save(post);

                auditService.log(
                        adminEmail,
                        null,
                        "HIDE_POST_FROM_REPORT",
                        "POST",
                        post.getId(),
                        oldStatus,
                        "HIDDEN"
                );
            }
        }

        report.setStatus(normalizedAction);

        reportRepository.save(report);

        auditService.log(
                adminEmail,
                null,
                "RESOLVE_REPORT",
                "REPORT",
                reportId,
                "PENDING",
                normalizedAction
        );

        return "Đã xử lý báo cáo với trạng thái: "
                + normalizedAction;
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

    private void validatePagination(
            int page,
            int size
    ) {

        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "Tham số phân trang không hợp lệ."
            );
        }
    }
}