package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.ModerationDto.ActionInfo;
import hcmute.edu.vn.backend.dto.ModerationDto.AuthorInfo;
import hcmute.edu.vn.backend.dto.ModerationDto.PageResult;
import hcmute.edu.vn.backend.dto.ModerationDto.QueueItem;
import hcmute.edu.vn.backend.dto.ModerationDto.ReportInfo;
import hcmute.edu.vn.backend.dto.ModerationDto.Stats;
import hcmute.edu.vn.backend.dto.ModerationDto.TargetDetail;
import hcmute.edu.vn.backend.entity.ContentReport;
import hcmute.edu.vn.backend.entity.ModerationAction;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.ContentReportRepository;
import hcmute.edu.vn.backend.repository.ModerationActionRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import hcmute.edu.vn.backend.service.ModerationTargets.Target;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Kiểm duyệt nội dung bị báo cáo (bài viết, bình luận bài viết, đánh giá địa điểm).
 * Dành cho ROLE_MODERATOR và ROLE_ADMIN.
 */
@Service
@RequiredArgsConstructor
public class ModerationService {

    static final int MAX_REASON_LENGTH = 500;
    static final int MAX_PAGE_SIZE = 50;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int EXCERPT_LENGTH = 140;

    private final ContentReportRepository reportRepository;
    private final ModerationActionRepository actionRepository;
    private final UserRepository userRepository;
    private final ModerationTargets targets;

    // =====================================================================
    // ĐỌC
    // =====================================================================

    @Transactional(readOnly = true)
    public Stats getStats(String email) {
        requireStaff(email);
        Map<String, Long> byType = new LinkedHashMap<>();
        byType.put(ModerationTargets.POST, 0L);
        byType.put(ModerationTargets.POST_COMMENT, 0L);
        byType.put(ModerationTargets.PLACE_REVIEW, 0L);
        long contents = 0;
        for (Object[] row : reportRepository.countPendingGroupsByType()) {
            long n = ((Number) row[1]).longValue();
            byType.merge((String) row[0], n, Long::sum);
            contents += n;
        }
        return new Stats(
                contents,
                reportRepository.countByStatus(ContentReport.PENDING),
                byType,
                actionRepository.countByCreatedAtAfter(LocalDate.now().atStartOfDay()));
    }

    /** Hàng đợi: mỗi nội dung đang có báo cáo chờ xử lý là một dòng, nhiều báo cáo nhất lên đầu. */
    @Transactional(readOnly = true)
    public PageResult<QueueItem> getQueue(String email, String type, Integer page, Integer size) {
        requireStaff(email);
        String filter = (type == null || type.isBlank()) ? null : ModerationTargets.normalizeType(type);
        int p = normalizePage(page);
        int s = normalizeSize(size);

        long total = 0;
        for (Object[] row : reportRepository.countPendingGroupsByType()) {
            if (filter == null || filter.equals(row[0])) {
                total += ((Number) row[1]).longValue();
            }
        }

        List<QueueItem> items = new java.util.ArrayList<>();
        for (Object[] row : reportRepository.findPendingGroups(filter, PageRequest.of(p, s))) {
            String targetType = (String) row[0];
            Long targetId = ((Number) row[1]).longValue();
            long count = ((Number) row[2]).longValue();
            LocalDateTime last = (LocalDateTime) row[3];

            Target target = targets.load(targetType, targetId, false);
            items.add(new QueueItem(
                    targetType, targetId, count, last,
                    pendingReasons(targetType, targetId),
                    !target.exists(), target.hidden(),
                    excerpt(target),
                    toAuthor(target.author())));
        }
        return new PageResult<>(items, p, s, total);
    }

    @Transactional(readOnly = true)
    public TargetDetail getDetail(String email, String type, Long id) {
        User actor = requireStaff(email);
        String t = ModerationTargets.normalizeType(type);
        Target target = targets.load(t, id, false);

        // Nội dung đã xóa mà chưa từng có báo cáo / thao tác nào -> thật sự không có gì để xem.
        if (!target.exists()
                && reportRepository.findAllByTarget(t, id).isEmpty()
                && actionRepository.findByTarget(t, id).isEmpty()) {
            throw new NoSuchElementException("Không tìm thấy nội dung.");
        }
        return buildDetail(actor, target);
    }

    @Transactional(readOnly = true)
    public PageResult<ActionInfo> getActions(String email, Integer page, Integer size) {
        requireStaff(email);
        int p = normalizePage(page);
        int s = normalizeSize(size);
        List<ModerationAction> rows = actionRepository.findRecent(PageRequest.of(p, s));
        return new PageResult<>(toActionInfos(rows), p, s, actionRepository.count());
    }

    // =====================================================================
    // THAO TÁC
    // =====================================================================

    /** Ẩn nội dung (bắt buộc có lý do - tác giả sẽ thấy lý do này) và đóng các báo cáo đang chờ. */
    @Transactional
    public TargetDetail hide(String email, String type, Long id, String reason) {
        User actor = requireStaff(email);
        String t = ModerationTargets.normalizeType(type);
        Target target = targets.load(t, id, true);

        requireExists(target);
        assertCanModerate(actor, target);
        if (target.hidden()) {
            throw new IllegalStateException("Nội dung này đã bị ẩn từ trước.");
        }
        String note = validateReason(reason, true);

        targets.hide(target, actor, note);
        int closed = reportRepository.closePending(t, id, ContentReport.RESOLVED, "HIDDEN", actor, LocalDateTime.now());
        log(actor, ModerationAction.HIDE, target, note, closed);

        return buildDetail(actor, targets.load(t, id, false));
    }

    /** Khôi phục nội dung đã ẩn (ví dụ ẩn nhầm). */
    @Transactional
    public TargetDetail restore(String email, String type, Long id, String reason) {
        User actor = requireStaff(email);
        String t = ModerationTargets.normalizeType(type);
        Target target = targets.load(t, id, true);

        requireExists(target);
        assertCanModerate(actor, target);
        if (!target.hidden()) {
            throw new IllegalStateException("Nội dung này không bị ẩn.");
        }
        String note = validateReason(reason, false);

        targets.restore(target, actor, note);
        log(actor, ModerationAction.RESTORE, target, note, 0);

        return buildDetail(actor, targets.load(t, id, false));
    }

    /** Bỏ qua báo cáo: nội dung không vi phạm, đóng các báo cáo đang chờ. Nội dung đã bị xóa vẫn bỏ qua được. */
    @Transactional
    public TargetDetail dismiss(String email, String type, Long id, String reason) {
        User actor = requireStaff(email);
        String t = ModerationTargets.normalizeType(type);
        Target target = targets.load(t, id, true);

        if (target.exists()) {
            assertCanModerate(actor, target);
        }
        String note = validateReason(reason, false);

        int closed = reportRepository.closePending(t, id, ContentReport.REJECTED, "DISMISSED", actor, LocalDateTime.now());
        if (closed == 0) {
            throw new IllegalStateException("Không còn báo cáo nào đang chờ xử lý cho nội dung này.");
        }
        log(actor, ModerationAction.DISMISS, target, note, closed);

        return buildDetail(actor, targets.load(t, id, false));
    }

    // =====================================================================
    // QUYỀN
    // =====================================================================

    static boolean isStaff(User user) {
        return user != null && ("ROLE_ADMIN".equals(user.getRole()) || "ROLE_MODERATOR".equals(user.getRole()));
    }

    /** Trả về lý do KHÔNG được kiểm duyệt (null nếu được). */
    static String denyReason(User actor, User author) {
        if (author == null) {
            return null;
        }
        if (actor.getId().equals(author.getId())) {
            return "Bạn không thể kiểm duyệt nội dung của chính mình.";
        }
        if (isStaff(author) && !"ROLE_ADMIN".equals(actor.getRole())) {
            return "Chỉ quản trị viên mới được kiểm duyệt nội dung của quản trị viên hoặc kiểm duyệt viên.";
        }
        return null;
    }

    private void assertCanModerate(User actor, Target target) {
        String deny = denyReason(actor, target.author());
        if (deny != null) {
            throw new AccessDeniedException(deny);
        }
    }

    private void requireExists(Target target) {
        if (!target.exists()) {
            throw new IllegalStateException("Nội dung này không còn tồn tại hoặc đã bị xóa.");
        }
    }

    private User requireStaff(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AccessDeniedException("Không tìm thấy người dùng."));
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new AccessDeniedException("Tài khoản hiện không hoạt động.");
        }
        if (!isStaff(user)) {
            throw new AccessDeniedException("Bạn không có quyền kiểm duyệt.");
        }
        return user;
    }

    // =====================================================================
    // HELPER
    // =====================================================================

    private String validateReason(String raw, boolean required) {
        String reason = raw == null ? "" : raw.trim();
        if (reason.isEmpty()) {
            if (required) {
                throw new IllegalArgumentException("Vui lòng nhập lý do ẩn nội dung.");
            }
            return null;
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Lý do tối đa " + MAX_REASON_LENGTH + " ký tự.");
        }
        return reason;
    }

    private void log(User actor, String action, Target target, String reason, int reportCount) {
        ModerationAction entry = new ModerationAction();
        entry.setActor(actor);
        entry.setAction(action);
        entry.setTargetType(target.type());
        entry.setTargetId(target.id());
        entry.setTargetAuthorId(target.author() == null ? null : target.author().getId());
        entry.setReason(reason);
        entry.setReportCount(reportCount);
        actionRepository.save(entry);
    }

    private Map<String, Long> pendingReasons(String type, Long id) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Object[] row : reportRepository.countPendingReasons(type, id)) {
            String code = row[0] == null ? "OTHER" : (String) row[0]; // báo cáo cũ chưa có reason_code
            map.merge(code, ((Number) row[1]).longValue(), Long::sum);
        }
        return map;
    }

    private TargetDetail buildDetail(User actor, Target target) {
        String deny = target.exists() ? denyReason(actor, target.author()) : "Nội dung này không còn tồn tại.";

        List<ReportInfo> reports = reportRepository.findAllByTarget(target.type(), target.id()).stream()
                .map(r -> new ReportInfo(
                        r.getId(), r.getReasonCode(), r.getReason(), r.getStatus(), r.getResolution(),
                        r.getReporter().getFullName(), r.getReporter().getUsername(),
                        r.getCreatedAt(), r.getReviewedAt()))
                .toList();
        List<ActionInfo> history = toActionInfos(actionRepository.findByTarget(target.type(), target.id()));

        return new TargetDetail(
                target.type(), target.id(), !target.exists(), target.hidden(),
                target.text(), target.rating(), target.locationName(), target.postId(),
                target.media(), target.createdAt(), toAuthor(target.author()),
                target.hidden() ? target.moderationNote() : null, target.moderatedAt(),
                deny == null, deny, reports, history);
    }

    private List<ActionInfo> toActionInfos(List<ModerationAction> rows) {
        Set<Long> authorIds = rows.stream()
                .map(ModerationAction::getTargetAuthorId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> authorNames = new HashMap<>();
        if (!authorIds.isEmpty()) {
            for (User u : userRepository.findAllById(authorIds)) {
                authorNames.put(u.getId(), u.getFullName());
            }
        }
        return rows.stream().map(a -> new ActionInfo(
                a.getId(), a.getAction(), a.getTargetType(), a.getTargetId(), a.getReason(), a.getReportCount(),
                a.getActor() == null ? null : a.getActor().getFullName(),
                a.getActor() == null ? null : a.getActor().getUsername(),
                a.getTargetAuthorId() == null ? null : authorNames.get(a.getTargetAuthorId()),
                a.getCreatedAt())).toList();
    }

    private AuthorInfo toAuthor(User u) {
        return u == null ? null : new AuthorInfo(u.getId(), u.getFullName(), u.getUsername(), u.getAvatar(), u.getRole());
    }

    private String excerpt(Target target) {
        if (!target.exists()) {
            return null;
        }
        String text = target.text() == null ? "" : target.text().trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return target.media().isEmpty() ? "" : "(Chỉ có ảnh / video)";
        }
        return text.length() > EXCERPT_LENGTH ? text.substring(0, EXCERPT_LENGTH) + "…" : text;
    }

    private int normalizePage(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    private int normalizeSize(Integer size) {
        if (size == null || size < 1) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
