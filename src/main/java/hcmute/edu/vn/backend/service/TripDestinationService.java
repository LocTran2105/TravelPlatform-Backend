package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.DestinationRequest;
import hcmute.edu.vn.backend.entity.TripDay;
import hcmute.edu.vn.backend.entity.TripDestination;
import hcmute.edu.vn.backend.repository.TripDayRepository;
import hcmute.edu.vn.backend.repository.TripDestinationRepository;
import hcmute.edu.vn.backend.repository.TripMemberRepository; // Thêm import này
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
}