package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.TripRequest;
import hcmute.edu.vn.backend.dto.TripSummaryResponse;
import hcmute.edu.vn.backend.entity.*;
import hcmute.edu.vn.backend.repository.*;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class TripService {

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TripDayRepository tripDayRepository;

    @Autowired
    private TripDestinationRepository tripDestinationRepository;

    @Autowired
    private TripMemberRepository tripMemberRepository;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private TripInvitationService invitationService;

    @Autowired
    private TripAccessService accessService;

    @Autowired
    private TripActivityService activityService;

    private static final Set<String> VISIBILITIES =
            Set.of("PUBLIC", "FOLLOWERS", "FRIENDS", "PRIVATE");

    // 1. Logic tạo chuyến đi mới (Tích hợp tự động tạo Ngày + Thêm Bạn đồng hành)
    @Transactional
    public Trip createTrip(TripRequest request, String userEmail) throws Exception {
        User owner = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng hiện tại!"));

        if (request.getStartDate() != null && request.getEndDate() != null) {
            if (request.getEndDate().isBefore(request.getStartDate())) {
                throw new Exception("Ngày kết thúc không được nhỏ hơn ngày bắt đầu!");
            }
        }

        // Kiểm tra email bạn đồng hành TRƯỚC khi tạo gì cả: email sai định dạng / quá nhiều
        // người thì báo lỗi ngay. Email chưa có tài khoản vẫn hợp lệ (họ sẽ nhận thư mời).
        List<String> inviteEmails = invitationService.prepareEmails(request.getCompanionEmails(), owner);

        Trip trip = new Trip();
        trip.setUser(owner);
        trip.setDestination(request.getDestination());

        String finalTitle = (request.getTitle() != null && !request.getTitle().trim().isEmpty())
                ? request.getTitle().trim()
                : "Chuyến đi đến " + (request.getDestination() != null ? request.getDestination() : "vùng đất mới");
        trip.setTitle(finalTitle);

        trip.setStartDate(request.getStartDate());
        trip.setEndDate(request.getEndDate());
        trip.setTotalBudget(request.getTotalBudget() != null ? request.getTotalBudget() : BigDecimal.ZERO);

        // visibility là nguồn sự thật duy nhất. Trước đây cờ isPublic từ client có thể
        // làm chuyến đi PRIVATE vẫn bị coi là công khai.
        String visibility = (request.getVisibility() == null || request.getVisibility().isBlank())
                ? "PRIVATE"
                : parseVisibility(request.getVisibility());
        applyVisibility(trip, visibility);

        // QUAN TRỌNG: Khởi tạo danh sách ngay từ đầu để tránh lỗi Null và OrphanRemoval của Hibernate
        trip.setTripDays(new ArrayList<>());
        trip.setMembers(new ArrayList<>());

        // --- BƯỚC A: CHỦ CHUYẾN ĐI LÀ THÀNH VIÊN ĐẦU TIÊN ---
        TripMember ownerMember = new TripMember();
        ownerMember.setTrip(trip);
        ownerMember.setUser(owner);
        ownerMember.setRole("OWNER");
        ownerMember.setStatus("ACCEPTED");
        trip.getMembers().add(ownerMember); // Đưa vào mảng thay vì gọi Repository.save()

        // Bạn đồng hành KHÔNG bị thêm thẳng vào chuyến đi: họ nhận email mời và chỉ trở thành
        // thành viên khi bấm "Tham gia". Vì vậy lúc tạo, chuyến đi mới có 1 thành viên (chủ).
        trip.setMemberCount(1);

        // --- BƯỚC B: TỰ ĐỘNG SINH CÁC NGÀY (TRIP_DAYS) ---
        if (request.getStartDate() != null && request.getEndDate() != null) {
            long totalDays = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
            totalDays = Math.min(totalDays, 60);

            for (int i = 0; i < totalDays; i++) {
                TripDay day = new TripDay();
                day.setTrip(trip);
                day.setDayNumber(i + 1);
                day.setDayDate(request.getStartDate().plusDays(i));
                trip.getTripDays().add(day); // Đưa vào mảng
            }
        } else {
            TripDay defaultDay = new TripDay();
            defaultDay.setTrip(trip);
            defaultDay.setDayNumber(1);
            defaultDay.setDayDate(LocalDate.now());
            trip.getTripDays().add(defaultDay);
        }

        // BƯỚC C: LƯU TOÀN BỘ BẰNG 1 LỆNH DUY NHẤT
        // Nhờ CascadeType.ALL, Hibernate sẽ tự động lặp qua mảng Members và TripDays để INSERT xuống Database
        Trip savedTrip = tripRepository.save(trip);

        // BƯỚC D: TẠO LỜI MỜI + GỬI EMAIL (email chỉ gửi sau khi giao dịch commit thành công)
        invitationService.invite(savedTrip, owner, inviteEmails, "EDITOR");

        return savedTrip;
    }

    // 2. Logic lấy danh sách chuyến đi của "Tôi"
    public List<Trip> getMyTrips(String userEmail) throws Exception {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng hiện tại!"));

        return tripRepository.findAllTripsOfUser(user.getId());
    }

    // 3. Lấy chi tiết toàn bộ chuyến đi (Cho phép Chủ sở hữu, Bạn đồng hành hoặc chuyến đi Public/Friends xem)
    public Trip getTripDetails(Long tripId, String userEmail) throws Exception {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        User viewer = userRepository.findByEmail(userEmail).orElse(null);
        if (!canView(trip, viewer, relationOf(viewer, trip.getUser()))) {
            throw new Exception("Bạn không có quyền xem chuyến đi này!");
        }
        return trip;
    }

    // 3a. Danh sách chuyến đi của MỘT NGƯỜI (trang cá nhân), chỉ trả những chuyến
    //     mà người xem được phép thấy. viewerEmail = null nghĩa là khách.
    public List<TripSummaryResponse> getUserTrips(String targetUsername, String viewerEmail) throws Exception {
        User target = userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng!"));

        User viewer = (viewerEmail != null)
                ? userRepository.findByEmail(viewerEmail).orElse(null)
                : null;

        // Tính quan hệ một lần cho cả danh sách thay vì mỗi chuyến đi
        ViewerRelation relation = relationOf(viewer, target);

        return tripRepository.findByUser_IdOrderByIdDesc(target.getId()).stream()
                .filter(trip -> canView(trip, viewer, relation))
                .map(this::toSummary)
                .toList();
    }

    // 3c. Đổi quyền hiển thị (chỉ chủ chuyến đi)
    @Transactional
    public String updateVisibility(Long tripId, String visibility, String userEmail) throws Exception {
        String normalized = parseVisibility(visibility);

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        if (!trip.getUser().getEmail().equalsIgnoreCase(userEmail)) {
            throw new Exception("Chỉ chủ chuyến đi mới có quyền đổi quyền hiển thị!");
        }

        applyVisibility(trip, normalized);
        tripRepository.save(trip);
        return normalized;
    }

    // 3b. Cập nhật tổng ngân sách (Chủ chuyến đi hoặc thành viên được phép chỉnh sửa)
    private static final BigDecimal MAX_MONEY = new BigDecimal("1000000000000");

    @Transactional
    public BigDecimal updateBudget(Long tripId, BigDecimal totalBudget, String userEmail) throws Exception {
        if (totalBudget == null || totalBudget.signum() < 0) {
            throw new Exception("Ngân sách phải lớn hơn hoặc bằng 0!");
        }
        if (totalBudget.compareTo(MAX_MONEY) > 0) {
            throw new Exception("Ngân sách quá lớn!");
        }

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        // Chủ chuyến đi hoặc tripmate được chỉnh sửa (người chỉ xem bị từ chối)
        accessService.requireEdit(trip, userEmail);

        BigDecimal oldBudget = trip.getTotalBudget();
        trip.setTotalBudget(totalBudget);
        tripRepository.save(trip);
        if (oldBudget == null || oldBudget.compareTo(totalBudget) != 0) {
            activityService.logCoalesced(trip, userEmail, TripActivityService.BUDGET_CHANGED, "TRIP", trip.getId(),
                    null, totalBudget.stripTrailingZeros().toPlainString());
        }
        return trip.getTotalBudget();
    }

    // 4. Nhân bản lịch trình
    @Transactional
    public Trip cloneTrip(Long originalTripId, String currentUserEmail) throws Exception {
        User currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng hiện tại!"));

        Trip originalTrip = tripRepository.findById(originalTripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi gốc để clone!"));

        if (!originalTrip.getIsPublic() || !"APPROVED".equals(originalTrip.getPublicStatus())) {
            if (!originalTrip.getUser().getId().equals(currentUser.getId())) {
                throw new Exception("Bạn không có quyền nhân bản chuyến đi này vì nó chưa được công khai!");
            }
        }

        Trip clonedTrip = new Trip();
        clonedTrip.setUser(currentUser);
        clonedTrip.setTitle("Bản sao của: " + originalTrip.getTitle());
        clonedTrip.setDestination(originalTrip.getDestination());
        clonedTrip.setStartDate(originalTrip.getStartDate());
        clonedTrip.setEndDate(originalTrip.getEndDate());
        clonedTrip.setMemberCount(1);
        clonedTrip.setTotalBudget(originalTrip.getTotalBudget());
        clonedTrip.setIsPublic(false);
        clonedTrip.setVisibility("PRIVATE");
        clonedTrip.setPublicStatus("DRAFT");
        clonedTrip.setClonedFromTripId(originalTrip.getId());

        Trip savedClonedTrip = tripRepository.save(clonedTrip);

        List<TripDay> originalDays = originalTrip.getTripDays();
        if (originalDays != null && !originalDays.isEmpty()) {
            for (TripDay originalDay : originalDays) {
                TripDay clonedDay = new TripDay();
                clonedDay.setTrip(savedClonedTrip);
                clonedDay.setDayNumber(originalDay.getDayNumber());
                clonedDay.setDayDate(originalDay.getDayDate());

                TripDay savedClonedDay = tripDayRepository.save(clonedDay);

                List<TripDestination> originalDestinations = originalDay.getDestinations();
                if (originalDestinations != null && !originalDestinations.isEmpty()) {
                    for (TripDestination originalDest : originalDestinations) {
                        TripDestination clonedDest = new TripDestination();
                        clonedDest.setTripDay(savedClonedDay);
                        clonedDest.setPlaceName(originalDest.getPlaceName());
                        clonedDest.setLatitude(originalDest.getLatitude());
                        clonedDest.setLongitude(originalDest.getLongitude());
                        clonedDest.setNote(originalDest.getNote());
                        clonedDest.setEstimatedCost(originalDest.getEstimatedCost());
                        clonedDest.setOrderIndex(originalDest.getOrderIndex());

                        tripDestinationRepository.save(clonedDest);
                    }
                }
            }
        }

        return getTripDetails(savedClonedTrip.getId(), currentUserEmail);
    }
    // 5. Xóa chuyến đi (chỉ chủ chuyến đi mới được xóa)
    @Transactional
    public void deleteTrip(Long tripId, String userEmail) throws Exception {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        if (!trip.getUser().getEmail().equalsIgnoreCase(userEmail)) {
            throw new Exception("Chỉ chủ chuyến đi mới có quyền xóa chuyến đi này!");
        }

        tripRepository.delete(trip);
    }
    // ---------------------------------------------------------------------
    // QUYỀN HIỂN THỊ (dùng chung ý nghĩa với bài viết)
    //   PUBLIC    : mọi người
    //   FOLLOWERS : người đang theo dõi chủ chuyến đi
    //   FRIENDS   : hai bên theo dõi lẫn nhau
    //   PRIVATE   : chỉ chủ chuyến đi và các thành viên (bạn đồng hành)
    // ---------------------------------------------------------------------

    private record ViewerRelation(boolean owner, boolean follows, boolean friend) {}

    private String parseVisibility(String visibility) throws Exception {
        String value = visibility == null ? "" : visibility.trim().toUpperCase();
        if (!VISIBILITIES.contains(value)) {
            throw new Exception("Quyền hiển thị không hợp lệ!");
        }
        return value;
    }

    private void applyVisibility(Trip trip, String visibility) {
        trip.setVisibility(visibility);
        boolean isPublic = "PUBLIC".equals(visibility);
        trip.setIsPublic(isPublic);
        trip.setPublicStatus(isPublic ? "APPROVED" : "DRAFT");
    }

    private ViewerRelation relationOf(User viewer, User owner) {
        if (viewer == null) {
            return new ViewerRelation(false, false, false);
        }
        if (viewer.getId().equals(owner.getId())) {
            return new ViewerRelation(true, false, false);
        }
        boolean follows = followRepository.existsByFollowerAndFollowed(viewer, owner);
        boolean friend = follows && followRepository.existsByFollowerAndFollowed(owner, viewer);
        return new ViewerRelation(false, follows, friend);
    }

    private boolean canView(Trip trip, User viewer, ViewerRelation relation) {
        if (relation.owner()) return true;

        String visibility = trip.getVisibility() == null ? "PRIVATE" : trip.getVisibility().toUpperCase();
        switch (visibility) {
            case "PUBLIC":
                return true;
            case "FOLLOWERS":
                if (relation.follows()) return true;
                break;
            case "FRIENDS":
                if (relation.friend()) return true;
                break;
            default:
                break;
        }

        // Thành viên của chuyến đi luôn được xem ("Only you & mates")
        return viewer != null
                && tripMemberRepository.existsByTripIdAndUserEmail(trip.getId(), viewer.getEmail());
    }

    private TripSummaryResponse toSummary(Trip trip) {
        TripSummaryResponse dto = new TripSummaryResponse();
        dto.setId(trip.getId());
        dto.setTitle(trip.getTitle());
        dto.setDestination(trip.getDestination());
        dto.setStartDate(trip.getStartDate());
        dto.setEndDate(trip.getEndDate());
        dto.setVisibility(trip.getVisibility());
        dto.setMemberCount(trip.getMemberCount());
        dto.setCoverImageUrl(trip.getCoverImageUrl());
        dto.setPlaceCount(countPlaces(trip));
        return dto;
    }

    /** Tổng số địa điểm của chuyến đi (cùng cách đếm với trang chủ: cộng điểm đến của mọi ngày). */
    private static int countPlaces(Trip trip) {
        if (trip.getTripDays() == null) return 0;
        int total = 0;
        for (TripDay day : trip.getTripDays()) {
            if (day != null && day.getDestinations() != null) total += day.getDestinations().size();
        }
        return total;
    }
}