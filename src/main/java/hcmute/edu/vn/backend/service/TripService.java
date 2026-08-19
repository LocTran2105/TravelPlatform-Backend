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

    // 4. Thêm một Ngày mới vào chuyến đi (Ví dụ: Day 1, Day 2)
    public TripDay addDayToTrip(Long tripId, Integer dayNumber, java.time.LocalDate dayDate, String userEmail) throws Exception {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        if (!trip.getUser().getEmail().equals(userEmail)) {
            throw new Exception("Chỉ chủ chuyến đi mới được thêm ngày!");
        }

        hcmute.edu.vn.backend.entity.TripDay tripDay = new hcmute.edu.vn.backend.entity.TripDay();
        tripDay.setTrip(trip);
        tripDay.setDayNumber(dayNumber);
        tripDay.setDayDate(dayDate);

        return tripDayRepository.save(tripDay);
    }

    // 5. Thêm một Điểm đến vào một Ngày cụ thể
    public TripDestination addDestinationToDay(Long dayId, DestinationRequest request, String userEmail) throws Exception {
        hcmute.edu.vn.backend.entity.TripDay tripDay = tripDayRepository.findById(dayId)
                .orElseThrow(() -> new Exception("Không tìm thấy ngày của chuyến đi!"));

        // Bảo mật: Kiểm tra xem user có phải chủ chuyến đi chứa ngày này không
        if (!tripDay.getTrip().getUser().getEmail().equals(userEmail)) {
            throw new Exception("Bạn không có quyền thêm địa điểm vào chuyến đi này!");
        }

        hcmute.edu.vn.backend.entity.TripDestination dest = new hcmute.edu.vn.backend.entity.TripDestination();
        dest.setTripDay(tripDay);
        dest.setPlaceName(request.getPlaceName());
        dest.setLatitude(request.getLatitude());
        dest.setLongitude(request.getLongitude());
        dest.setNote(request.getNote());
        dest.setEstimatedCost(request.getEstimatedCost());
        dest.setOrderIndex(request.getOrderIndex());

        return tripDestinationRepository.save(dest);
    }
}