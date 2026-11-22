package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.ShareDtos.*;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripMember;
import hcmute.edu.vn.backend.entity.TripShareLink;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.TripMemberRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import hcmute.edu.vn.backend.repository.TripShareLinkRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Nút Share: link chia sẻ theo vai trò + quản lý bạn đồng hành (tripmate).
 * Lời mời qua email nằm ở TripInvitationService.
 */
@Service
public class TripShareService {

    private static final Set<String> ROLES = Set.of("EDITOR", "VIEWER");
    /** Chặn lạm dụng link công khai làm phình danh sách thành viên. */
    private static final int MAX_MEMBERS_PER_TRIP = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TripShareLinkRepository linkRepository;
    private final TripMemberRepository memberRepository;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final TripInvitationService invitationService;

    public TripShareService(TripShareLinkRepository linkRepository,
                            TripMemberRepository memberRepository,
                            TripRepository tripRepository,
                            UserRepository userRepository,
                            TripInvitationService invitationService) {
        this.linkRepository = linkRepository;
        this.memberRepository = memberRepository;
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.invitationService = invitationService;
    }

    // ==================================================================
    // 1. LINK CHIA SẺ (chỉ chủ chuyến đi)
    // ==================================================================

    /** Trả 2 token; link nào chưa có thì tạo ngay lần đầu chủ chuyến đi mở modal Share. */
    @Transactional
    public ShareLinks getLinks(Long tripId, String ownerEmail) {
        Trip trip = loadTrip(tripId);
        requireOwner(trip, ownerEmail);
        return new ShareLinks(
                ensureLink(trip, "EDITOR").getToken(),
                ensureLink(trip, "VIEWER").getToken());
    }

    /** Đổi token của một link: link cũ chết ngay. Thành viên đã vào rồi thì không bị ảnh hưởng. */
    @Transactional
    public ShareLinks resetLink(Long tripId, String role, String ownerEmail) {
        Trip trip = loadTrip(tripId);
        requireOwner(trip, ownerEmail);
        String r = normalizeRole(role);
        TripShareLink link = linkRepository.findByTripIdAndRole(tripId, r).orElse(null);
        if (link == null) {
            ensureLink(trip, r);
        } else {
            link.setToken(newToken());
            linkRepository.save(link);
        }
        return new ShareLinks(
                ensureLink(trip, "EDITOR").getToken(),
                ensureLink(trip, "VIEWER").getToken());
    }

    private TripShareLink ensureLink(Trip trip, String role) {
        Optional<TripShareLink> found = linkRepository.findByTripIdAndRole(trip.getId(), role);
        if (found.isPresent()) return found.get();
        TripShareLink link = new TripShareLink();
        link.setTrip(trip);
        link.setRole(role);
        link.setToken(newToken());
        return linkRepository.save(link);
    }

    // ==================================================================
    // 2. NGƯỜI CẦM LINK
    // ==================================================================

    /** Công khai: ai cầm link cũng xem được tên chuyến đi để biết mình sắp tham gia gì. */
    @Transactional(readOnly = true)
    public SharePreview preview(String token) {
        TripShareLink link = findLink(token);
        Trip trip = link.getTrip();
        return new SharePreview(trip.getTitle(), trip.getDestination(), trip.getStartDate(), trip.getEndDate(),
                trip.getUser().getFullName(), link.getRole());
    }

    /**
     * Tham gia bằng link (đã đăng nhập). Idempotent: bấm lại không tạo trùng.
     * Chỉ nâng quyền, không bao giờ hạ quyền; người đã bị chủ chuyến đi xóa thì không tự quay lại bằng link.
     */
    @Transactional
    public JoinResult join(String token, String email) {
        User user = requireUser(email);
        TripShareLink link = findLink(token);
        Trip trip = link.getTrip();

        if (trip.getUser().getId().equals(user.getId())) {
            return new JoinResult(trip.getId(), "OWNER", "OWNER");
        }

        Optional<TripMember> existing = memberRepository.findByTripIdAndUserId(trip.getId(), user.getId());
        String status;
        String finalRole;
        if (existing.isPresent()) {
            TripMember member = existing.get();
            if ("REMOVED".equals(member.getStatus())) {
                throw new InvitationException(HttpStatus.FORBIDDEN,
                        "Bạn đã bị chủ chuyến đi xóa khỏi chuyến đi này. Hãy nhờ họ mời lại.");
            }
            if ("ACCEPTED".equals(member.getStatus())) {
                if (rank(link.getRole()) > rank(member.getRole())) {
                    member.setRole(link.getRole());
                    memberRepository.save(member);
                    recount(trip);
                }
                status = "ALREADY";
            } else {
                requireCapacity(trip);
                member.setRole(link.getRole());
                member.setStatus("ACCEPTED");
                memberRepository.save(member);
                recount(trip);
                status = "JOINED";
            }
            finalRole = effectiveRole(member.getRole());
        } else {
            requireCapacity(trip);
            TripMember member = new TripMember();
            member.setTrip(trip);
            member.setUser(user);
            member.setRole(link.getRole());
            member.setStatus("ACCEPTED");
            memberRepository.save(member);
            recount(trip);
            status = "JOINED";
            finalRole = link.getRole();
        }
        return new JoinResult(trip.getId(), finalRole, status);
    }

    // ==================================================================
    // 3. QUẢN LÝ BẠN ĐỒNG HÀNH
    // ==================================================================

    /** Chủ chuyến đi và các tripmate đều xem được danh sách (không lộ email). */
    @Transactional(readOnly = true)
    public List<MemberInfo> listMembers(Long tripId, String email) {
        Trip trip = loadTrip(tripId);
        boolean isOwner = trip.getUser().getEmail().equalsIgnoreCase(email);
        if (!isOwner && !memberRepository.existsByTripIdAndUserEmail(tripId, email)) {
            throw new InvitationException(HttpStatus.FORBIDDEN, "Bạn không phải thành viên của chuyến đi này.");
        }
        List<MemberInfo> result = new ArrayList<>();
        User owner = trip.getUser();
        result.add(new MemberInfo(null, owner.getId(), owner.getUsername(), owner.getFullName(),
                owner.getAvatar(), "OWNER", true));

        List<TripMember> members = new ArrayList<>(memberRepository.findByTripId(tripId));
        members.sort(Comparator.comparing(TripMember::getId));
        for (TripMember m : members) {
            if (m.getUser().getId().equals(owner.getId())) continue; // dòng OWNER của chính chủ
            result.add(toInfo(m));
        }
        return result;
    }

    @Transactional
    public MemberInfo changeRole(Long tripId, Long memberId, String role, String ownerEmail) {
        Trip trip = loadTrip(tripId);
        requireOwner(trip, ownerEmail);
        TripMember member = loadMember(trip, memberId);
        member.setRole(normalizeRole(role));
        memberRepository.save(member);
        recount(trip);
        return toInfo(member);
    }

    /** Xóa khỏi chuyến đi (giữ dòng với trạng thái REMOVED để link cũ không đưa họ quay lại). */
    @Transactional
    public void removeMember(Long tripId, Long memberId, String ownerEmail) {
        Trip trip = loadTrip(tripId);
        requireOwner(trip, ownerEmail);
        TripMember member = loadMember(trip, memberId);
        member.setStatus("REMOVED");
        memberRepository.save(member);
        recount(trip);
    }

    private TripMember loadMember(Trip trip, Long memberId) {
        TripMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> notFound("Không tìm thấy thành viên."));
        if (!member.getTrip().getId().equals(trip.getId()) || !"ACCEPTED".equals(member.getStatus())) {
            throw notFound("Không tìm thấy thành viên.");
        }
        if (member.getUser().getId().equals(trip.getUser().getId()) || "OWNER".equals(member.getRole())) {
            throw new InvitationException(HttpStatus.BAD_REQUEST, "Không thể thay đổi chủ chuyến đi.");
        }
        return member;
    }

    // ==================================================================
    // HÀM HỖ TRỢ
    // ==================================================================

    private TripShareLink findLink(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            throw notFound("Link chia sẻ không tồn tại hoặc đã được thay đổi.");
        }
        return linkRepository.findByTokenWithTrip(token.trim())
                .orElseThrow(() -> notFound("Link chia sẻ không tồn tại hoặc đã được thay đổi."));
    }

    private Trip loadTrip(Long tripId) {
        return tripRepository.findById(tripId).orElseThrow(() -> notFound("Không tìm thấy chuyến đi!"));
    }

    private void requireOwner(Trip trip, String email) {
        if (email == null || !trip.getUser().getEmail().equalsIgnoreCase(email)) {
            throw new InvitationException(HttpStatus.FORBIDDEN, "Chỉ chủ chuyến đi mới được thực hiện thao tác này.");
        }
    }

    private User requireUser(String email) {
        if (email == null) throw new InvitationException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập.");
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new InvitationException(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập lại."));
    }

    private void requireCapacity(Trip trip) {
        if (memberRepository.countByTripIdAndStatus(trip.getId(), "ACCEPTED") >= MAX_MEMBERS_PER_TRIP) {
            throw new InvitationException(HttpStatus.CONFLICT, "Chuyến đi đã đạt số thành viên tối đa.");
        }
    }

    private void recount(Trip trip) {
        invitationService.recalculateMemberCount(trip);
    }

    private MemberInfo toInfo(TripMember m) {
        User u = m.getUser();
        return new MemberInfo(m.getId(), u.getId(), u.getUsername(), u.getFullName(), u.getAvatar(),
                effectiveRole(m.getRole()), false);
    }

    /** Vai trò MEMBER cũ (trước khi có phân quyền) được tính là EDITOR. */
    private static String effectiveRole(String role) {
        return "VIEWER".equals(role) ? "VIEWER" : "EDITOR";
    }

    private static String normalizeRole(String role) {
        String r = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(r)) {
            throw new InvitationException(HttpStatus.BAD_REQUEST, "Quyền không hợp lệ (chỉ EDITOR hoặc VIEWER).");
        }
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

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static InvitationException notFound(String msg) {
        return new InvitationException(HttpStatus.NOT_FOUND, msg);
    }
}
