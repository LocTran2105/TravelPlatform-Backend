package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.ModerationDto.ReportRequest;
import hcmute.edu.vn.backend.entity.ContentReport;
import hcmute.edu.vn.backend.entity.PlaceReview;
import hcmute.edu.vn.backend.entity.PostComment;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.ContentReportRepository;
import hcmute.edu.vn.backend.repository.PlaceReviewRepository;
import hcmute.edu.vn.backend.repository.PostCommentRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;

/** Người dùng báo cáo bài viết / bình luận / đánh giá địa điểm. */
@Service
@RequiredArgsConstructor
public class ReportService {

    static final Set<String> REASON_CODES = Set.of("SPAM", "HARASSMENT", "SENSITIVE", "MISINFORMATION", "OTHER");
    static final int MAX_DETAILS_LENGTH = 500;
    /** Tối đa số báo cáo mỗi người trong 1 giờ (chống spam báo cáo). */
    static final int MAX_REPORTS_PER_HOUR = 20;

    private final ContentReportRepository reportRepository;
    private final UserRepository userRepository;
    private final PostAccessService postAccessService;
    private final PostCommentRepository postCommentRepository;
    private final PlaceReviewRepository placeReviewRepository;

    @Transactional
    public void createReport(String email, ReportRequest request) {
        User reporter = requireActiveUser(email);
        if (request == null) {
            throw new IllegalArgumentException("Thiếu nội dung báo cáo.");
        }

        String type = ModerationTargets.normalizeType(request.targetType());
        if (request.targetId() == null) {
            throw new IllegalArgumentException("Thiếu nội dung cần báo cáo.");
        }
        Long targetId = request.targetId();

        String reasonCode = request.reasonCode() == null ? "" : request.reasonCode().trim().toUpperCase(Locale.ROOT);
        if (!REASON_CODES.contains(reasonCode)) {
            throw new IllegalArgumentException("Vui lòng chọn lý do báo cáo.");
        }
        String details = request.details() == null ? "" : request.details().trim();
        if (details.length() > MAX_DETAILS_LENGTH) {
            throw new IllegalArgumentException("Mô tả tối đa " + MAX_DETAILS_LENGTH + " ký tự.");
        }
        if ("OTHER".equals(reasonCode) && details.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng mô tả lý do khi chọn \"Khác\".");
        }

        // Nội dung phải tồn tại, đang hiển thị với người báo cáo và không phải của chính họ.
        Long authorId = requireReportableAndGetAuthorId(type, targetId, reporter);
        if (authorId.equals(reporter.getId())) {
            throw new IllegalArgumentException("Bạn không thể báo cáo nội dung của chính mình.");
        }

        if (reportRepository.existsByReporterIdAndTargetTypeAndTargetId(reporter.getId(), type, targetId)) {
            throw new IllegalStateException("Bạn đã báo cáo nội dung này rồi. Chúng tôi sẽ xem xét sớm.");
        }
        long recent = reportRepository.countByReporterIdAndCreatedAtAfter(
                reporter.getId(), LocalDateTime.now().minusHours(1));
        if (recent >= MAX_REPORTS_PER_HOUR) {
            throw new TooManyReportsException("Bạn gửi báo cáo quá nhanh. Vui lòng thử lại sau ít phút.");
        }

        ContentReport report = new ContentReport();
        report.setReporter(reporter);
        report.setTargetType(type);
        report.setTargetId(targetId);
        report.setReasonCode(reasonCode);
        report.setReason(details.isEmpty() ? null : details);
        try {
            reportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException e) {
            // 2 request cùng lúc: UNIQUE (reporter, target) chặn bản thứ hai.
            throw new IllegalStateException("Bạn đã báo cáo nội dung này rồi. Chúng tôi sẽ xem xét sớm.");
        }
    }

    // ------------------------------------------------------------------

    /** Trả về id tác giả nếu người báo cáo thật sự đang thấy được nội dung; ngược lại 404. */
    private Long requireReportableAndGetAuthorId(String type, Long targetId, User reporter) {
        switch (type) {
            case ModerationTargets.POST: {
                // 404 nếu bài không tồn tại, đã xóa, đã bị ẩn hoặc người này không có quyền xem.
                return postAccessService.requireViewablePost(targetId, reporter).getUser().getId();
            }
            case ModerationTargets.POST_COMMENT: {
                PostComment comment = postCommentRepository.findById(targetId)
                        .filter(c -> "PUBLISHED".equals(c.getStatus()))
                        .orElseThrow(() -> new NoSuchElementException("Không tìm thấy bình luận."));
                postAccessService.requireViewablePost(comment.getPost().getId(), reporter);
                return comment.getUser().getId();
            }
            case ModerationTargets.PLACE_REVIEW: {
                PlaceReview review = placeReviewRepository.findById(targetId)
                        .filter(r -> "PUBLISHED".equals(r.getStatus()))
                        .orElseThrow(() -> new NoSuchElementException("Không tìm thấy đánh giá."));
                return review.getUser().getId();
            }
            default:
                throw new IllegalArgumentException("Loại nội dung không được hỗ trợ.");
        }
    }

    private User requireActiveUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy người dùng."));
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new AccessDeniedException("Tài khoản hiện không hoạt động.");
        }
        return user;
    }
}
