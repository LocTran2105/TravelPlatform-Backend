package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.TripRequest;
import hcmute.edu.vn.backend.entity.*;
import hcmute.edu.vn.backend.repository.*;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

        String visibility = request.getVisibility() != null ? request.getVisibility().toUpperCase() : "PRIVATE";
        trip.setVisibility(visibility);
        boolean isPublic = "PUBLIC".equals(visibility) || Boolean.TRUE.equals(request.getIsPublic());
        trip.setIsPublic(isPublic);
        trip.setPublicStatus(isPublic ? "APPROVED" : "DRAFT");

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
            for (String companionEmail : request.getCompanionEmails()) {
                String cleanEmail = companionEmail.trim().toLowerCase();
                if (cleanEmail.equalsIgnoreCase(owner.getEmail())) continue;

                Optional<User> companionOpt = userRepository.findByEmail(cleanEmail);
                if (companionOpt.isPresent()) {
                    User companion = companionOpt.get();
                    TripMember member = new TripMember();
                    member.setTrip(trip);
                    member.setUser(companion);
                    member.setRole("EDITOR");
                    member.setStatus("ACCEPTED");
                    trip.getMembers().add(member);
                }
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

        return tripRepository.findByUserId(user.getId());
    }

    // 3. Lấy chi tiết toàn bộ chuyến đi (Cho phép Chủ sở hữu, Bạn đồng hành hoặc chuyến đi Public/Friends xem)
    public Trip getTripDetails(Long tripId, String userEmail) throws Exception {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        boolean isOwner = trip.getUser().getEmail().equalsIgnoreCase(userEmail);
        boolean isCompanion = tripMemberRepository.existsByTripIdAndUserEmail(tripId, userEmail);
        boolean isPublicOrFriends = Boolean.TRUE.equals(trip.getIsPublic()) || "FRIENDS".equalsIgnoreCase(trip.getVisibility());

        if (!isOwner && !isCompanion && !isPublicOrFriends) {
            throw new Exception("Bạn không có quyền xem chuyến đi này!");
        }
        return trip;
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
}