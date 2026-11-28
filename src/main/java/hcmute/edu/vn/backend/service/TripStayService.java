package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.TripStayDtos.StayRequest;
import hcmute.edu.vn.backend.dto.TripStayDtos.StayResponse;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripStay;
import hcmute.edu.vn.backend.repository.PlaceRepository;
import hcmute.edu.vn.backend.repository.TripStayRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** Mục "Nơi ở" của chuyến đi: khách sạn/homestay theo từng khoảng đêm. */
@Service
@RequiredArgsConstructor
public class TripStayService {

    private static final int MAX_STAYS_PER_TRIP = 30;

    private final TripStayRepository stayRepository;
    private final PlaceRepository placeRepository;
    private final UserRepository userRepository;
    private final TripAccessService accessService;
    private final TripActivityService activityService;

    @Transactional(readOnly = true)
    public List<StayResponse> list(Long tripId, String email) {
        accessService.requireMember(tripId, email);
        return stayRepository.findByTripIdOrderByCheckInDateAscIdAsc(tripId).stream().map(TripStayService::toResponse).toList();
    }

    /** Nơi ở của chuyến đi cho các service khác (đã kiểm tra quyền ở nơi gọi). */
    @Transactional(readOnly = true)
    public List<TripStay> staysOf(Long tripId) {
        return stayRepository.findByTripIdOrderByCheckInDateAscIdAsc(tripId);
    }

    @Transactional
    public StayResponse create(Long tripId, StayRequest request, String email) {
        Trip trip = accessService.requireEdit(tripId, email);
        if (stayRepository.findByTripIdOrderByCheckInDateAscIdAsc(tripId).size() >= MAX_STAYS_PER_TRIP) {
            throw new TripAccessException(HttpStatus.BAD_REQUEST, "Chuyến đi đã có quá nhiều nơi ở.");
        }
        TripStay stay = new TripStay();
        stay.setTripId(tripId);
        stay.setCreatedBy(userRepository.findByEmail(email).map(u -> u.getId()).orElse(null));
        apply(stay, request, trip);
        TripStay saved = stayRepository.save(stay);
        activityService.log(trip, email, TripActivityService.STAY_ADDED, "STAY", saved.getId(),
                saved.getName(), nightsLabel(saved));
        return toResponse(saved);
    }

    @Transactional
    public StayResponse update(Long tripId, Long stayId, StayRequest request, String email) {
        Trip trip = accessService.requireEdit(tripId, email);
        TripStay stay = load(tripId, stayId);
        apply(stay, request, trip);
        TripStay saved = stayRepository.save(stay);
        activityService.logCoalesced(trip, email, TripActivityService.STAY_UPDATED, "STAY", saved.getId(),
                saved.getName(), nightsLabel(saved));
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long tripId, Long stayId, String email) {
        Trip trip = accessService.requireEdit(tripId, email);
        TripStay stay = load(tripId, stayId);
        stayRepository.delete(stay);
        activityService.log(trip, email, TripActivityService.STAY_REMOVED, "STAY", stayId, stay.getName(), null);
    }

    private TripStay load(Long tripId, Long stayId) {
        TripStay stay = stayRepository.findById(stayId)
                .orElseThrow(() -> new TripAccessException(HttpStatus.NOT_FOUND, "Không tìm thấy nơi ở."));
        if (!Objects.equals(stay.getTripId(), tripId)) {
            throw new TripAccessException(HttpStatus.NOT_FOUND, "Không tìm thấy nơi ở.");
        }
        return stay;
    }

    private void apply(TripStay stay, StayRequest request, Trip trip) {
        if (request == null) throw bad("Thiếu thông tin nơi ở.");

        Place place = request.placeId() == null ? null : placeRepository.findById(request.placeId()).orElse(null);
        String name = trimToNull(request.name());
        if (name == null && place != null) name = place.getName();
        if (name == null) throw bad("Vui lòng nhập tên nơi ở.");
        if (name.length() > 255) name = name.substring(0, 255);

        BigDecimal lat = request.latitude() != null ? request.latitude() : place != null ? place.getLatitude() : null;
        BigDecimal lng = request.longitude() != null ? request.longitude() : place != null ? place.getLongitude() : null;
        if ((lat == null) != (lng == null)) throw bad("Tọa độ nơi ở không hợp lệ.");
        if (lat != null && (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0 || lng.abs().compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw bad("Tọa độ nơi ở không hợp lệ.");
        }

        LocalDate checkIn = request.checkInDate();
        LocalDate checkOut = request.checkOutDate();
        if (checkIn == null || checkOut == null) throw bad("Vui lòng chọn ngày nhận phòng và trả phòng.");
        if (!checkOut.isAfter(checkIn)) throw bad("Ngày trả phòng phải sau ngày nhận phòng.");
        if (ChronoUnit.DAYS.between(checkIn, checkOut) > 60) throw bad("Một nơi ở tối đa 60 đêm.");
        // Cho lệch 1 ngày so với chuyến đi: nhận phòng tối hôm trước, trả phòng sáng hôm sau.
        if (trip.getStartDate() != null && checkIn.isBefore(trip.getStartDate().minusDays(1))) {
            throw bad("Ngày nhận phòng phải nằm trong thời gian chuyến đi.");
        }
        if (trip.getEndDate() != null && checkOut.isAfter(trip.getEndDate().plusDays(1))) {
            throw bad("Ngày trả phòng phải nằm trong thời gian chuyến đi.");
        }

        String address = trimToNull(request.address());
        if (address == null && place != null) address = place.getAddress();
        if (address != null && address.length() > 500) address = address.substring(0, 500);
        String note = trimToNull(request.note());
        if (note != null && note.length() > 2000) note = note.substring(0, 2000);

        stay.setPlaceId(place != null ? place.getId() : null);
        stay.setName(name);
        stay.setAddress(address);
        stay.setLatitude(lat);
        stay.setLongitude(lng);
        stay.setCheckInDate(checkIn);
        stay.setCheckOutDate(checkOut);
        stay.setNote(note);
    }

    static StayResponse toResponse(TripStay stay) {
        int nights = (int) ChronoUnit.DAYS.between(stay.getCheckInDate(), stay.getCheckOutDate());
        return new StayResponse(stay.getId(), stay.getTripId(), stay.getPlaceId(), stay.getName(), stay.getAddress(),
                stay.getLatitude(), stay.getLongitude(), stay.getCheckInDate(), stay.getCheckOutDate(), nights, stay.getNote());
    }

    private static String nightsLabel(TripStay stay) {
        return stay.getCheckInDate() + " → " + stay.getCheckOutDate();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static TripAccessException bad(String message) {
        return new TripAccessException(HttpStatus.BAD_REQUEST, message);
    }
}
