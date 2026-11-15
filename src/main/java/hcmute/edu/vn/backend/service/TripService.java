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

        // --- BƯỚC A: THÊM THÀNH VIÊN ĐỒNG HÀNH ---
        TripMember ownerMember = new TripMember();
        ownerMember.setTrip(trip);
        ownerMember.setUser(owner);
        ownerMember.setRole("OWNER");
        ownerMember.setStatus("ACCEPTED");
        trip.getMembers().add(ownerMember); // Đưa vào mảng thay vì gọi Repository.save()

        if (request.getCompanionEmails() != null && !request.getCompanionEmails().isEmpty()) {
            List<String> notFoundEmails = new ArrayList<>();
            Set<String> seenEmails = new HashSet<>();

            for (String companionEmail : request.getCompanionEmails()) {
                if (companionEmail == null) continue;
                String cleanEmail = companionEmail.trim().toLowerCase();

                // Bỏ qua email rỗng, email của chủ chuyến đi và email bị trùng
                if (cleanEmail.isEmpty()
                        || cleanEmail.equalsIgnoreCase(owner.getEmail())
                        || !seenEmails.add(cleanEmail)) continue;

                Optional<User> companionOpt = userRepository.findByEmail(cleanEmail);
                if (companionOpt.isPresent()) {
                    TripMember member = new TripMember();
                    member.setTrip(trip);
                    member.setUser(companionOpt.get());
                    member.setRole("EDITOR");
                    member.setStatus("ACCEPTED");
                    trip.getMembers().add(member);
                } else {
                    notFoundEmails.add(cleanEmail);
                }
            }

            if (!notFoundEmails.isEmpty()) {
                throw new Exception("Không tìm thấy tài khoản với email: " + String.join(", ", notFoundEmails));
            }
        }
        trip.setMemberCount(trip.getMembers().size());

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
        return tripRepository.save(trip);
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

        boolean isOwner = trip.getUser().getEmail().equalsIgnoreCase(userEmail);
        boolean isCompanion = tripMemberRepository.existsByTripIdAndUserEmail(tripId, userEmail);
        if (!isOwner && !isCompanion) {
            throw new Exception("Bạn không có quyền chỉnh sửa ngân sách chuyến đi này!");
        }

        trip.setTotalBudget(totalBudget);
        tripRepository.save(trip);
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
    // 6. Kiểm tra email đã đăng ký tài khoản chưa (dùng cho ô Tripmates)
    public boolean isEmailRegistered(String email) {
        if (email == null || email.trim().isEmpty()) return false;
        return userRepository.findByEmail(email.trim().toLowerCase()).isPresent();
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
        return dto;
    }
}