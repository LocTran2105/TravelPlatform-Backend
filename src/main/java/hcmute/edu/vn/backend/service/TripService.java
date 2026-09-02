package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.DestinationRequest;
import hcmute.edu.vn.backend.dto.TripRequest;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripDay;
import hcmute.edu.vn.backend.entity.TripDestination;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.TripDayRepository;
import hcmute.edu.vn.backend.repository.TripDestinationRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

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


    // 1. Logic tạo chuyến đi mới
    public Trip createTrip(TripRequest request, String userEmail) throws Exception {
        // Tìm User trong DB dựa vào email lấy từ Token
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng hiện tại!"));

        Trip trip = new Trip();
        trip.setUser(user); // Gán người sở hữu chuyến đi
        trip.setTitle(request.getTitle());
        trip.setStartDate(request.getStartDate());
        trip.setEndDate(request.getEndDate());
        trip.setMemberCount(request.getMemberCount());
        trip.setTotalBudget(request.getTotalBudget());

        // Nếu client không gửi isPublic, mặc định là false
        trip.setIsPublic(request.getIsPublic() != null ? request.getIsPublic() : false);
        trip.setPublicStatus("DRAFT"); // Trạng thái mặc định

        return tripRepository.save(trip);
    }

    // 2. Logic lấy danh sách chuyến đi của "Tôi"
    public List<Trip> getMyTrips(String userEmail) throws Exception {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng hiện tại!"));

        return tripRepository.findByUserId(user.getId());
    }

    // 3. Lấy chi tiết toàn bộ chuyến đi (bao gồm các ngày và điểm đến)
    public Trip getTripDetails(Long tripId, String userEmail) throws Exception {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        // Kiểm tra bảo mật: Chỉ chủ sở hữu hoặc chuyến đi đang Public mới được xem
        if (!trip.getUser().getEmail().equals(userEmail) && !trip.getIsPublic()) {
            throw new Exception("Bạn không có quyền xem chuyến đi này!");
        }
        return trip;
    }

    //4. Nhân bản lịch trình
    @Transactional
    public Trip cloneTrip(Long originalTripId, String currentUserEmail) throws Exception {
        // 1. Tìm User sẽ sở hữu bản clone
        User currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng hiện tại!"));

        // 2. Tìm chuyến đi gốc
        Trip originalTrip = tripRepository.findById(originalTripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi gốc để clone!"));

        // 3. Kiểm tra quyền: Chỉ được clone nếu chuyến đi đó đang Public và đã được Approve
        if (!originalTrip.getIsPublic() || !"APPROVED".equals(originalTrip.getPublicStatus())) {
            // Cho phép clone nếu chính là chuyến đi của mình (tuỳ thuộc vào luồng nghiệp vụ bạn muốn)
            if (!originalTrip.getUser().getId().equals(currentUser.getId())) {
                throw new Exception("Bạn không có quyền nhân bản chuyến đi này vì nó chưa được công khai!");
            }
        }

        // 4. Bắt đầu Clone Trip (Tạo mới hoàn toàn chứ không update)
        Trip clonedTrip = new Trip();
        clonedTrip.setUser(currentUser);
        clonedTrip.setTitle("Bản sao của: " + originalTrip.getTitle());
        clonedTrip.setStartDate(originalTrip.getStartDate());
        clonedTrip.setEndDate(originalTrip.getEndDate());
        clonedTrip.setMemberCount(originalTrip.getMemberCount());
        clonedTrip.setTotalBudget(originalTrip.getTotalBudget());
        clonedTrip.setIsPublic(false); // Bản clone ban đầu phải là private
        clonedTrip.setPublicStatus("DRAFT");
        clonedTrip.setClonedFromTripId(originalTrip.getId()); // Lưu vết: Clone từ đâu

        // Lưu chuyến đi clone vào DB để lấy được ID mới
        Trip savedClonedTrip = tripRepository.save(clonedTrip);

        // 5. Clone từng Ngày (TripDay)
        List<TripDay> originalDays = originalTrip.getTripDays();
        if (originalDays != null && !originalDays.isEmpty()) {
            for (TripDay originalDay : originalDays) {
                TripDay clonedDay = new TripDay();
                clonedDay.setTrip(savedClonedTrip); // Gắn vào Trip vừa clone
                clonedDay.setDayNumber(originalDay.getDayNumber());
                clonedDay.setDayDate(originalDay.getDayDate());

                TripDay savedClonedDay = tripDayRepository.save(clonedDay);

                // 6. Clone từng Điểm đến (TripDestination) trong Ngày đó
                List<TripDestination> originalDestinations = originalDay.getDestinations();
                if (originalDestinations != null && !originalDestinations.isEmpty()) {
                    for (TripDestination originalDest : originalDestinations) {
                        TripDestination clonedDest = new TripDestination();
                        clonedDest.setTripDay(savedClonedDay); // Gắn vào Day vừa clone
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

        // Trả về bản sao đã hoàn thiện (để cẩn thận, có thể query lại từ db để lấy full data trả về)
        return getTripDetails(savedClonedTrip.getId(), currentUserEmail);
    }
}