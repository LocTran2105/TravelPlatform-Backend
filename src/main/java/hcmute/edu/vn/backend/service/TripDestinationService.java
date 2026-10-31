package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.DestinationRequest;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripDay;
import hcmute.edu.vn.backend.entity.TripDestination;
import hcmute.edu.vn.backend.repository.TripDayRepository;
import hcmute.edu.vn.backend.repository.TripDestinationRepository;
import hcmute.edu.vn.backend.repository.TripMemberRepository; // Thêm import này
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class TripDestinationService {
    @Autowired
    private TripDestinationRepository tripDestinationRepository;

    @Autowired
    private TripDayRepository tripDayRepository;

    @Autowired
    private TripMemberRepository tripMemberRepository; // Inject thêm Repository này

    public TripDestination addDestinationToDay(Long dayId, DestinationRequest request, String userEmail) throws Exception {
        TripDay tripDay = tripDayRepository.findById(dayId)
                .orElseThrow(() -> new Exception("Không tìm thấy ngày của chuyến đi!"));

        Long tripId = tripDay.getTrip().getId();
        boolean isOwner = tripDay.getTrip().getUser().getEmail().equalsIgnoreCase(userEmail);
        // Kiểm tra xem user hiện tại có phải là thành viên của chuyến đi không
        boolean isCompanion = tripMemberRepository.existsByTripIdAndUserEmail(tripId, userEmail);

        if (!isOwner && !isCompanion) {
            throw new Exception("Bạn không có quyền chỉnh sửa hành trình này!");
        }

        TripDestination dest = new TripDestination();
        dest.setTripDay(tripDay);
        dest.setPlaceName(request.getPlaceName());
        dest.setLatitude(request.getLatitude());
        dest.setLongitude(request.getLongitude());
        dest.setNote(request.getNote());
        dest.setEstimatedCost(request.getEstimatedCost());
        dest.setOrderIndex(request.getOrderIndex());

        return tripDestinationRepository.save(dest);
    }

    private static final BigDecimal MAX_MONEY = new BigDecimal("1000000000000");

    @Transactional
    public void updateDestinationProperty(Long destinationId, String field, Object value, String userEmail) throws Exception {
        TripDestination dest = tripDestinationRepository.findById(destinationId)
                .orElseThrow(() -> new Exception("Không tìm thấy địa điểm trong lịch trình!"));

        // Chỉ chủ chuyến đi hoặc thành viên mới được sửa (giống kiểm tra ở addDestinationToDay)
        Trip trip = dest.getTripDay().getTrip();
        boolean isOwner = trip.getUser().getEmail().equalsIgnoreCase(userEmail);
        boolean isCompanion = tripMemberRepository.existsByTripIdAndUserEmail(trip.getId(), userEmail);
        if (!isOwner && !isCompanion) {
            throw new Exception("Bạn không có quyền chỉnh sửa hành trình này!");
        }

        switch (field) {
            case "note":
                dest.setNote(value != null ? value.toString() : null);
                break;
            case "isVisited":
                dest.setIsVisited(Boolean.valueOf(value.toString()));
                break;
            case "time": // Tương ứng với time_range trong DB
                dest.setTimeRange(value != null ? value.toString() : null);
                break;
            case "cost":
                dest.setCost(value != null ? value.toString() : null);
                break;
            case "estimatedCost": // Chi phí dự kiến (số) - dùng để tính tổng ngân sách
                dest.setEstimatedCost(parseMoney(value));
                break;
            default:
                throw new Exception("Trường cần cập nhật không hợp lệ!");
        }

        tripDestinationRepository.save(dest);
    }

    private BigDecimal parseMoney(Object value) throws Exception {
        if (value == null || value.toString().isBlank()) return BigDecimal.ZERO;
        BigDecimal amount;
        try {
            amount = new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new Exception("Số tiền không hợp lệ!");
        }
        if (amount.signum() < 0) throw new Exception("Số tiền không được âm!");
        if (amount.compareTo(MAX_MONEY) > 0) throw new Exception("Số tiền quá lớn!");
        return amount;
    }
}