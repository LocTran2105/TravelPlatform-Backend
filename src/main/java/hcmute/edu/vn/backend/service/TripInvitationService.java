package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.InvitationDtos.*;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripInvitation;
import hcmute.edu.vn.backend.entity.TripMember;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.TripInvitationRepository;
import hcmute.edu.vn.backend.repository.TripMemberRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import hcmute.edu.vn.backend.service.MailService.TripInvitationMail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lời mời tham gia chuyến đi qua email.
 *
 *  - Mời được cả email chưa có tài khoản. Người được mời KHÔNG bị thêm vào chuyến đi
 *    cho tới khi tự bấm "Tham gia" (tránh bị tính vào chia tiền khi chưa đồng ý).
 *  - Token trong link là chuỗi ngẫu nhiên 256 bit; DB chỉ lưu bản băm SHA-256.
 *  - Chỉ tài khoản có đúng email được mời mới chấp nhận được.
 *  - Chỉ chủ chuyến đi mới mời thêm / thu hồi / gửi lại lời mời.
 */
@Service
public class TripInvitationService {

    static final int MAX_EMAILS_PER_REQUEST = 20;
    static final int MAX_PENDING_PER_TRIP = 20;
    static final int MAX_INVITES_PER_HOUR = 30;
    static final long RESEND_COOLDOWN_SECONDS = 60;

    private static final Set<String> INVITE_ROLES = Set.of("EDITOR", "VIEWER");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TripInvitationRepository invitationRepository;
    private final TripMemberRepository memberRepository;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final MailService mailService;
    private final long expiryDays;

    public TripInvitationService(TripInvitationRepository invitationRepository,
                                 TripMemberRepository memberRepository,
                                 TripRepository tripRepository,
                                 UserRepository userRepository,
                                 MailService mailService,
                                 @Value("${app.invitation.expiry-days:7}") long expiryDays) {
        this.invitationRepository = invitationRepository;
        this.memberRepository = memberRepository;
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.mailService = mailService;
        this.expiryDays = expiryDays > 0 ? expiryDays : 7;
    }

    // ==================================================================
    // 1. MỜI
    // ==================================================================

    /**
     * Chuẩn hóa + kiểm tra danh sách email (chữ thường, bỏ trùng, bỏ email của người mời).
     * Gọi TRƯỚC khi lưu chuyến đi để email sai thì không tạo gì cả.
     */
    public List<String> prepareEmails(List<String> raw, User inviter) {
        if (raw == null) return List.of();
        Set<String> result = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) continue;
            String email = item.trim().toLowerCase(Locale.ROOT);
            if (email.isEmpty()) continue;
            if (email.length() > 255 || !EMAIL_PATTERN.matcher(email).matches()) {
                throw bad("Email không hợp lệ: " + clip(email));
            }
            if (email.equalsIgnoreCase(inviter.getEmail())) continue;
            result.add(email);
        }
        if (result.size() > MAX_EMAILS_PER_REQUEST) {
            throw bad("Mỗi lần chỉ mời được tối đa " + MAX_EMAILS_PER_REQUEST + " người.");
        }
        return new ArrayList<>(result);
    }

    /**
     * Tạo lời mời và gửi email (sau khi giao dịch commit thành công).
     * Người gọi phải tự kiểm tra quyền; emails phải qua prepareEmails.
     */
    @Transactional
    public InviteResult invite(Trip trip, User inviter, List<String> emails, String role) {
        String inviteRole = normalizeRole(role);
        List<TripInvitationInfo> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (emails == null || emails.isEmpty()) return new InviteResult(created, skipped);

        LocalDateTime now = LocalDateTime.now();
        long sentLastHour = invitationRepository
                .countByInvitedByIdAndLastSentAtAfter(inviter.getId(), now.minusHours(1));
        long pending = invitationRepository.countByTripIdAndStatus(trip.getId(), "PENDING");
        List<TripInvitationMail> mails = new ArrayList<>();

        for (String email : emails) {
            // Đã là chủ chuyến đi / thành viên, hoặc đã có lời mời đang chờ => bỏ qua
            if (isOwnerOrMember(trip, email)
                    || invitationRepository.findFirstByTripIdAndEmailAndStatus(trip.getId(), email, "PENDING").isPresent()) {
                skipped.add(email);
                continue;
            }
            if (sentLastHour + created.size() >= MAX_INVITES_PER_HOUR) {
                throw new InvitationException(HttpStatus.TOO_MANY_REQUESTS,
                        "Bạn đã gửi quá nhiều lời mời trong 1 giờ qua. Vui lòng thử lại sau.");
            }
            if (pending + created.size() >= MAX_PENDING_PER_TRIP) {
                throw bad("Chuyến đi đã có quá nhiều lời mời đang chờ (tối đa " + MAX_PENDING_PER_TRIP + ").");
            }

            String token = newToken();
            TripInvitation inv = new TripInvitation();
            inv.setTrip(trip);
            inv.setEmail(email);
            inv.setRole(inviteRole);
            inv.setTokenHash(hash(token));
            inv.setStatus("PENDING");
            inv.setInvitedBy(inviter);
            inv.setExpiresAt(now.plusDays(expiryDays));
            inv.setLastSentAt(now);
            inv.setSendCount(1);
            invitationRepository.save(inv);

            created.add(toInfo(inv, now));
            mails.add(toMail(inv, token));
        }

        afterCommit(() -> mails.forEach(mailService::sendTripInvitation));
        return new InviteResult(created, skipped);
    }

    /** Chủ chuyến đi mời thêm người (dùng cho màn Share / Manage tripmates): theo email và/hoặc theo người dùng. */
    @Transactional
    public InviteResult inviteByOwner(Long tripId, String ownerEmail, InviteRequest request) {
        Trip trip = loadTrip(tripId);
        User owner = requireOwner(trip, ownerEmail);

        List<String> raw = new ArrayList<>();
        if (request != null && request.emails() != null) raw.addAll(request.emails());
        if (request != null && request.userIds() != null && !request.userIds().isEmpty()) {
            if (request.userIds().size() > MAX_EMAILS_PER_REQUEST) {
                throw bad("Mỗi lần chỉ mời được tối đa " + MAX_EMAILS_PER_REQUEST + " người.");
            }
            for (User u : userRepository.findAllById(request.userIds())) {
                if ("ACTIVE".equals(u.getStatus())) raw.add(u.getEmail());
            }
        }
        List<String> emails = prepareEmails(raw, owner);
        if (emails.isEmpty()) throw bad("Vui lòng chọn ít nhất một người hoặc nhập email hợp lệ.");
        return invite(trip, owner, emails, request.role());
    }

    // ==================================================================
    // 2. XEM TRƯỚC (công khai, chỉ cần có token)
    // ==================================================================

    @Transactional(readOnly = true)
    public Preview preview(String rawToken) {
        TripInvitation inv = findByToken(rawToken, false);
        Trip trip = inv.getTrip();
        LocalDateTime now = LocalDateTime.now();
        return new Preview(
                effectiveStatus(inv, now),
                trip.getTitle(),
                trip.getDestination(),
                trip.getStartDate(),
                trip.getEndDate(),
                inv.getInvitedBy().getFullName(),
                inv.getRole(),
                maskEmail(inv.getEmail()),
                userRepository.existsByEmail(inv.getEmail()),
                inv.getExpiresAt());
    }

    // ==================================================================
    // 3. CHẤP NHẬN / TỪ CHỐI
    // ==================================================================

    @Transactional
    public AcceptResult accept(String rawToken, String email) {
        TripInvitation inv = findByToken(rawToken, true);
        return doAccept(inv, requireUser(email), true);
    }

    @Transactional
    public AcceptResult acceptById(Long id, String email) {
        TripInvitation inv = invitationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> notFound("Lời mời không tồn tại."));
        return doAccept(inv, requireUser(email), false);
    }

    @Transactional
    public AcceptResult decline(String rawToken, String email) {
        TripInvitation inv = findByToken(rawToken, true);
        return doDecline(inv, requireUser(email), true);
    }

    @Transactional
    public AcceptResult declineById(Long id, String email) {
        TripInvitation inv = invitationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> notFound("Lời mời không tồn tại."));
        return doDecline(inv, requireUser(email), false);
    }

    private AcceptResult doAccept(TripInvitation inv, User user, boolean byToken) {
        checkRecipient(inv, user, byToken);
        Trip trip = inv.getTrip();
        LocalDateTime now = LocalDateTime.now();

        switch (inv.getStatus()) {
            case "ACCEPTED":
                // Bấm lại link cũ: nếu vẫn còn là thành viên thì cứ vào chuyến đi
                if (isOwnerOrMember(trip, user.getEmail())) {
                    return new AcceptResult(trip.getId(), "ACCEPTED");
                }
                throw gone("Bạn không còn là thành viên của chuyến đi này. Hãy nhờ chủ chuyến đi mời lại.");
            case "DECLINED":
                throw gone("Bạn đã từ chối lời mời này. Hãy nhờ người mời gửi lại nếu đổi ý.");
            case "REVOKED":
                throw gone("Lời mời này đã bị thu hồi.");
            default:
                break;
        }
        if (inv.getExpiresAt().isBefore(now)) {
            throw gone("Lời mời đã hết hạn. Hãy nhờ người mời gửi lại.");
        }

        boolean isTripOwner = trip.getUser().getId().equals(user.getId());
        if (!isTripOwner) {
            Optional<TripMember> existing = memberRepository.findByTripIdAndUserId(trip.getId(), user.getId());
            if (existing.isEmpty()) {
                TripMember member = new TripMember();
                member.setTrip(trip);
                member.setUser(user);
                member.setRole(inv.getRole());
                member.setStatus("ACCEPTED");
                memberRepository.save(member);
            } else {
                TripMember member = existing.get();
                if ("ACCEPTED".equals(member.getStatus())) {
                    // Đã là thành viên: chỉ nâng quyền, không bao giờ hạ quyền
                    if (rank(inv.getRole()) > rank(member.getRole())) member.setRole(inv.getRole());
                } else {
                    member.setRole(inv.getRole());
                    member.setStatus("ACCEPTED");
                }
                memberRepository.save(member);
            }
            recalculateMemberCount(trip);
        }

        inv.setStatus("ACCEPTED");
        inv.setAcceptedBy(user);
        inv.setRespondedAt(now);
        invitationRepository.save(inv);
        return new AcceptResult(trip.getId(), "ACCEPTED");
    }

    private AcceptResult doDecline(TripInvitation inv, User user, boolean byToken) {
        checkRecipient(inv, user, byToken);
        switch (inv.getStatus()) {
            case "DECLINED":
                return new AcceptResult(null, "DECLINED"); // idempotent
            case "ACCEPTED":
                throw new InvitationException(HttpStatus.CONFLICT,
                        "Bạn đã tham gia chuyến đi này rồi.");
            case "REVOKED":
                throw gone("Lời mời này đã bị thu hồi.");
            default:
                break;
        }
        inv.setStatus("DECLINED");
        inv.setRespondedAt(LocalDateTime.now());
        invitationRepository.save(inv);
        return new AcceptResult(null, "DECLINED");
    }

    /** Chỉ tài khoản có đúng email được mời mới được phản hồi. */
    private void checkRecipient(TripInvitation inv, User user, boolean byToken) {
        if (inv.getEmail().equalsIgnoreCase(user.getEmail())) return;
        if (!byToken) throw notFound("Lời mời không tồn tại."); // không để lộ id nào tồn tại
        throw new InvitationException(HttpStatus.FORBIDDEN,
                "Lời mời này được gửi tới " + maskEmail(inv.getEmail())
                        + ". Bạn đang đăng nhập bằng một tài khoản khác.");
    }

    // ==================================================================
    // 4. DANH SÁCH
    // ==================================================================

    /** Lời mời còn hiệu lực gửi tới người dùng hiện tại. */
    @Transactional(readOnly = true)
    public List<Mine> listMine(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        List<Mine> result = new ArrayList<>();
        for (TripInvitation inv : invitationRepository.findActiveForEmail(normalized, LocalDateTime.now())) {
            Trip trip = inv.getTrip();
            result.add(new Mine(inv.getId(), trip.getId(), trip.getTitle(), trip.getDestination(),
                    trip.getStartDate(), trip.getEndDate(),
                    inv.getInvitedBy().getFullName(), inv.getInvitedBy().getAvatar(),
                    inv.getRole(), inv.getExpiresAt()));
        }
        return result;
    }

    /** Lời mời đang chờ của một chuyến đi: chủ chuyến đi và thành viên được xem. */
    @Transactional(readOnly = true)
    public List<TripInvitationInfo> listForTrip(Long tripId, String email) {
        Trip trip = loadTrip(tripId);
        boolean isOwner = trip.getUser().getEmail().equalsIgnoreCase(email);
        if (!isOwner && !memberRepository.existsByTripIdAndUserEmail(tripId, email)) {
            throw new InvitationException(HttpStatus.FORBIDDEN, "Bạn không có quyền xem lời mời của chuyến đi này.");
        }
        LocalDateTime now = LocalDateTime.now();
        List<TripInvitationInfo> result = new ArrayList<>();
        for (TripInvitation inv : invitationRepository.findPendingByTripId(tripId)) {
            result.add(toInfo(inv, now));
        }
        return result;
    }

    // ==================================================================
    // 5. THU HỒI / GỬI LẠI (chủ chuyến đi)
    // ==================================================================

    @Transactional
    public void revoke(Long tripId, Long invitationId, String ownerEmail) {
        TripInvitation inv = loadOwnedInvitation(tripId, invitationId, ownerEmail);
        switch (inv.getStatus()) {
            case "ACCEPTED":
                throw new InvitationException(HttpStatus.CONFLICT,
                        "Người này đã tham gia. Hãy xóa họ khỏi danh sách thành viên thay vì thu hồi lời mời.");
            case "PENDING":
                inv.setStatus("REVOKED");
                inv.setRespondedAt(LocalDateTime.now());
                invitationRepository.save(inv);
                break;
            default:
                break; // DECLINED / REVOKED: không cần làm gì
        }
    }

    /** Gửi lại: sinh token mới (link cũ vô hiệu), gia hạn, gửi email mới. */
    @Transactional
    public TripInvitationInfo resend(Long tripId, Long invitationId, String ownerEmail) {
        TripInvitation inv = loadOwnedInvitation(tripId, invitationId, ownerEmail);
        if (!"PENDING".equals(inv.getStatus())) {
            throw bad("Lời mời này không còn chờ phản hồi. Hãy mời lại bằng email.");
        }
        LocalDateTime now = LocalDateTime.now();
        long waited = Duration.between(inv.getLastSentAt(), now).getSeconds();
        if (waited < RESEND_COOLDOWN_SECONDS) {
            throw new InvitationException(HttpStatus.TOO_MANY_REQUESTS,
                    "Vui lòng đợi " + (RESEND_COOLDOWN_SECONDS - waited) + " giây rồi gửi lại.");
        }
        long sentLastHour = invitationRepository
                .countByInvitedByIdAndLastSentAtAfter(inv.getTrip().getUser().getId(), now.minusHours(1));
        if (sentLastHour >= MAX_INVITES_PER_HOUR) {
            throw new InvitationException(HttpStatus.TOO_MANY_REQUESTS,
                    "Bạn đã gửi quá nhiều lời mời trong 1 giờ qua. Vui lòng thử lại sau.");
        }

        String token = newToken();
        inv.setTokenHash(hash(token));
        inv.setExpiresAt(now.plusDays(expiryDays));
        inv.setLastSentAt(now);
        inv.setSendCount(inv.getSendCount() + 1);
        invitationRepository.save(inv);

        TripInvitationMail mail = toMail(inv, token);
        afterCommit(() -> mailService.sendTripInvitation(mail));
        return toInfo(inv, now);
    }

    private TripInvitation loadOwnedInvitation(Long tripId, Long invitationId, String ownerEmail) {
        TripInvitation inv = invitationRepository.findByIdForUpdate(invitationId)
                .orElseThrow(() -> notFound("Không tìm thấy lời mời."));
        if (!inv.getTrip().getId().equals(tripId)) throw notFound("Không tìm thấy lời mời.");
        requireOwner(inv.getTrip(), ownerEmail);
        return inv;
    }

    // ==================================================================
    // HÀM HỖ TRỢ
    // ==================================================================

    private Trip loadTrip(Long tripId) {
        return tripRepository.findById(tripId).orElseThrow(() -> notFound("Không tìm thấy chuyến đi!"));
    }

    private User requireOwner(Trip trip, String email) {
        if (email == null || !trip.getUser().getEmail().equalsIgnoreCase(email)) {
            throw new InvitationException(HttpStatus.FORBIDDEN, "Chỉ chủ chuyến đi mới được quản lý lời mời.");
        }
        return trip.getUser();
    }

    private User requireUser(String email) {
        if (email == null) throw new InvitationException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new InvitationException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập lại."));
    }

    private TripInvitation findByToken(String rawToken, boolean forUpdate) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) {
            throw notFound("Lời mời không tồn tại hoặc đã bị thu hồi.");
        }
        String hash = hash(rawToken.trim());
        Optional<TripInvitation> found = forUpdate
                ? invitationRepository.findByTokenHashForUpdate(hash)
                : invitationRepository.findByTokenHashWithTrip(hash);
        return found.orElseThrow(() -> notFound("Lời mời không tồn tại hoặc đã bị thu hồi."));
    }

    private boolean isOwnerOrMember(Trip trip, String email) {
        return trip.getUser().getEmail().equalsIgnoreCase(email)
                || memberRepository.existsByTripIdAndUserEmail(trip.getId(), email);
    }

    /**
     * memberCount = số thành viên đã chấp nhận, không tính người chỉ xem
     * (+ chủ chuyến đi nếu chưa có dòng trip_members, vd chuyến đi nhân bản).
     */
    public void recalculateMemberCount(Trip trip) {
        long joined = memberRepository.countByTripIdAndStatusAndRoleNot(trip.getId(), "ACCEPTED", "VIEWER");
        boolean ownerHasRow = memberRepository.findByTripIdAndUserId(trip.getId(), trip.getUser().getId())
                .filter(m -> "ACCEPTED".equals(m.getStatus())).isPresent();
        trip.setMemberCount((int) joined + (ownerHasRow ? 0 : 1));
        tripRepository.save(trip);
    }

    private static String normalizeRole(String role) {
        String r = (role == null || role.isBlank()) ? "EDITOR" : role.trim().toUpperCase(Locale.ROOT);
        if (!INVITE_ROLES.contains(r)) throw bad("Quyền không hợp lệ (chỉ EDITOR hoặc VIEWER).");
        return r;
    }

    private static int rank(String role) {
        if (role == null) return 0;
        switch (role) {
            case "OWNER": return 4;
            case "EDITOR": return 3;
            case "MEMBER": return 2;
            case "VIEWER": return 1;
            default: return 0;
        }
    }

    private static String effectiveStatus(TripInvitation inv, LocalDateTime now) {
        if ("PENDING".equals(inv.getStatus()) && inv.getExpiresAt().isBefore(now)) return "EXPIRED";
        return inv.getStatus();
    }

    private TripInvitationInfo toInfo(TripInvitation inv, LocalDateTime now) {
        return new TripInvitationInfo(inv.getId(), inv.getEmail(), inv.getRole(),
                effectiveStatus(inv, now), inv.getInvitedBy().getFullName(),
                inv.getExpiresAt(), inv.getLastSentAt(), inv.getSendCount());
    }

    private TripInvitationMail toMail(TripInvitation inv, String token) {
        Trip trip = inv.getTrip();
        return new TripInvitationMail(inv.getEmail(), inv.getInvitedBy().getFullName(),
                trip.getTitle(), trip.getDestination(), trip.getStartDate(), trip.getEndDate(),
                inv.getRole(), token, inv.getExpiresAt());
    }

    /** Chỉ gửi mail sau khi giao dịch commit, để không gửi thư cho lời mời bị rollback. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String maskEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at < 0) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        return (local.isEmpty() ? "" : local.charAt(0)) + "***" + domain;
    }

    // Rút gọn + bỏ ký tự điều khiển trước khi đưa email người dùng nhập vào thông báo lỗi
    private static String clip(String s) {
        String t = s.replaceAll("[\\p{Cntrl}]+", " ");
        return t.length() > 60 ? t.substring(0, 60) + "..." : t;
    }

    private static InvitationException bad(String msg) {
        return new InvitationException(HttpStatus.BAD_REQUEST, msg);
    }

    private static InvitationException notFound(String msg) {
        return new InvitationException(HttpStatus.NOT_FOUND, msg);
    }

    private static InvitationException gone(String msg) {
        return new InvitationException(HttpStatus.GONE, msg);
    }
}
