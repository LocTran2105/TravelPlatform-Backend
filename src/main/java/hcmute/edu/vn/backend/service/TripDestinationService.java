package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.DestinationRequest;
import hcmute.edu.vn.backend.entity.TripDay;
import hcmute.edu.vn.backend.entity.TripDestination;
import hcmute.edu.vn.backend.repository.TripDayRepository;
import hcmute.edu.vn.backend.repository.TripDestinationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TripDestinationService {
    @Autowired
    private TripDestinationRepository tripDestinationRepository;

    @Autowired
    private TripDayRepository tripDayRepository;

    public TripDestination addDestinationToDay(Long dayId, DestinationRequest request, String userEmail) throws Exception {
        TripDay tripDay = tripDayRepository.findById(dayId)
                .orElseThrow(() -> new Exception("Không tìm thấy ngày của chuyến đi!"));

        if (!tripDay.getTrip().getUser().getEmail().equals(userEmail)) {
            throw new Exception("Bạn không có quyền thêm địa điểm vào chuyến đi này!");
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