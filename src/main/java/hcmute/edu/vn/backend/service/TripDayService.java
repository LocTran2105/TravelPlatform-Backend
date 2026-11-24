package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripDay;
import hcmute.edu.vn.backend.repository.TripDayRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;

@Service
public class TripDayService {
    @Autowired
    private TripDayRepository tripDayRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TripAccessService accessService;

    @Autowired
    private TripActivityService activityService;

    @Transactional(rollbackFor = Exception.class)
    public TripDay addDayToTrip(Long tripId, Integer dayNumber, LocalDate dayDate, String userEmail) throws Exception {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        // Chủ chuyến đi hoặc tripmate được chỉnh sửa (người chỉ xem bị từ chối)
        accessService.requireEdit(trip, userEmail);

        TripDay tripDay = new TripDay();
        tripDay.setTrip(trip);
        tripDay.setDayNumber(dayNumber);
        tripDay.setDayDate(dayDate);

        TripDay saved = tripDayRepository.save(tripDay);
        activityService.log(trip, userEmail, TripActivityService.DAY_ADDED, "DAY", saved.getId(),
                "Day " + dayNumber, dayDate != null ? dayDate.toString() : null);
        return saved;
    }
}