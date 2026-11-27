package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.ModeratorDto.UserSummary;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.ModeratorUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Quản trị viên cấp / thu hồi vai trò ROLE_MODERATOR.
 *
 * <p>Cột users.role chỉ chứa MỘT vai trò, nên chỉ cấp cho tài khoản ROLE_TRAVELLER đang ACTIVE
 * (không ghi đè vai trò đối tác hay quản trị viên) và khi thu hồi thì trả về ROLE_TRAVELLER.
 * AuthTokenFilter đọc vai trò từ DB mỗi request nên thay đổi có hiệu lực ở phía API ngay lập tức.
 */
@Service
@RequiredArgsConstructor
public class AdminModeratorService {

    static final String ROLE_ADMIN = "ROLE_ADMIN";
    static final String ROLE_MODERATOR = "ROLE_MODERATOR";
    static final String ROLE_TRAVELLER = "ROLE_TRAVELLER";

    static final int MIN_KEYWORD_LENGTH = 2;
    static final int MAX_KEYWORD_LENGTH = 50;
    static final int MAX_CANDIDATES = 10;

    private final ModeratorUserRepository users;
    private final PartnerAuditService auditService;

    @Transactional
    public List<UserSummary> listModerators(String adminEmail) {
        requireAdmin(adminEmail);
        return users.findByRoleOrderByFullNameAsc(ROLE_MODERATOR).stream().map(AdminModeratorService::toSummary).toList();
    }

    /** Gợi ý tài khoản có thể cấp quyền. Từ khóa quá ngắn trả danh sách rỗng. */
    @Transactional
    public List<UserSummary> searchCandidates(String adminEmail, String keyword) {
        requireAdmin(adminEmail);
        String q = keyword == null ? "" : keyword.trim();
        if (q.length() < MIN_KEYWORD_LENGTH) {
            return List.of();
        }
        if (q.length() > MAX_KEYWORD_LENGTH) {
            q = q.substring(0, MAX_KEYWORD_LENGTH);
        }
        return users.searchCandidates(q, PageRequest.of(0, MAX_CANDIDATES)).stream().map(AdminModeratorService::toSummary).toList();
    }

    @Transactional
    public UserSummary grant(String adminEmail, Long userId) {
        requireAdmin(adminEmail);
        if (userId == null) {
            throw new IllegalArgumentException("Vui lòng chọn người dùng cần cấp quyền.");
        }
        User target = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy người dùng."));

        if (ROLE_MODERATOR.equals(target.getRole())) {
            throw new IllegalStateException("Người dùng này đã là kiểm duyệt viên.");
        }
        if (!ROLE_TRAVELLER.equals(target.getRole())) {
            throw new IllegalStateException("Chỉ cấp được quyền kiểm duyệt cho tài khoản người dùng thường. "
                    + "Tài khoản này đang giữ vai trò " + describe(target.getRole()) + ".");
        }
        if (!"ACTIVE".equalsIgnoreCase(target.getStatus())) {
            throw new IllegalStateException("Tài khoản này chưa ở trạng thái hoạt động nên chưa thể cấp quyền.");
        }

        String oldRole = target.getRole();
        target.setRole(ROLE_MODERATOR);
        UserSummary saved = toSummary(users.save(target));
        auditService.log(adminEmail, null, "ADMIN_GRANT_MODERATOR", "USER", target.getId(), oldRole, ROLE_MODERATOR);
        return saved;
    }

    @Transactional
    public UserSummary revoke(String adminEmail, Long userId) {
        requireAdmin(adminEmail);
        if (userId == null) {
            throw new IllegalArgumentException("Thiếu người dùng cần thu hồi quyền.");
        }
        User target = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy người dùng."));

        if (!ROLE_MODERATOR.equals(target.getRole())) {
            throw new IllegalStateException("Người dùng này không phải kiểm duyệt viên.");
        }

        target.setRole(ROLE_TRAVELLER);
        UserSummary saved = toSummary(users.save(target));
        auditService.log(adminEmail, null, "ADMIN_REVOKE_MODERATOR", "USER", target.getId(), ROLE_MODERATOR, ROLE_TRAVELLER);
        return saved;
    }

    // ------------------------------------------------------------------

    private void requireAdmin(String email) {
        User caller = users.findByEmail(email == null ? "" : email)
                .orElseThrow(() -> new AccessDeniedException("Bạn cần đăng nhập."));
        if (!ROLE_ADMIN.equals(caller.getRole())) {
            throw new AccessDeniedException("Chỉ quản trị viên mới được quản lý kiểm duyệt viên.");
        }
    }

    private static String describe(String role) {
        if (ROLE_ADMIN.equals(role)) return "quản trị viên";
        if ("ROLE_PARTNER".equals(role)) return "đối tác";
        return String.valueOf(role);
    }

    private static UserSummary toSummary(User u) {
        return new UserSummary(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(), u.getAvatar(),
                u.getRole(), u.getStatus(), u.getCreatedAt());
    }
}
