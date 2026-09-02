package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripDay;
import hcmute.edu.vn.backend.repository.TripDayRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDate;

@Service
public class TripDayService {
    @Autowired
    private TripDayRepository tripDayRepository;

    @Autowired
    private TripRepository tripRepository;

    public TripDay addDayToTrip(Long tripId, Integer dayNumber, LocalDate dayDate, String userEmail) throws Exception {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        if (!trip.getUser().getEmail().equals(userEmail)) {
            throw new Exception("Chỉ chủ chuyến đi mới được thêm ngày!");
        }

        TripDay tripDay = new TripDay();
        tripDay.setTrip(trip);
        tripDay.setDayNumber(dayNumber);
        tripDay.setDayDate(dayDate);

        return tripDayRepository.save(tripDay);
    }
}