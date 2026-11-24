package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripDay;
import hcmute.edu.vn.backend.entity.TripDestination;
import hcmute.edu.vn.backend.entity.TripMember;
import hcmute.edu.vn.backend.repository.TripDayRepository;
import hcmute.edu.vn.backend.repository.TripDestinationRepository;
import hcmute.edu.vn.backend.repository.TripMemberRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Nơi DUY NHẤT quyết định ai được sửa chuyến đi.
 *   OWNER  : chủ chuyến đi
 *   EDITOR : tripmate được chỉnh sửa (vai trò MEMBER cũ được coi như EDITOR)
 *   VIEWER : tripmate chỉ xem
 */
@Service
public class TripAccessService {

    private final TripRepository tripRepository;
    private final TripMemberRepository memberRepository;
    private final TripDayRepository tripDayRepository;
    private final TripDestinationRepository tripDestinationRepository;

    public TripAccessService(TripRepository tripRepository,
                             TripMemberRepository memberRepository,
                             TripDayRepository tripDayRepository,
                             TripDestinationRepository tripDestinationRepository) {
        this.tripRepository = tripRepository;
        this.memberRepository = memberRepository;
        this.tripDayRepository = tripDayRepository;
        this.tripDestinationRepository = tripDestinationRepository;
    }

    /** OWNER, EDITOR, VIEWER hoặc null nếu không phải chủ / thành viên đã chấp nhận. */
    @Transactional(readOnly = true)
    public String roleOf(Trip trip, String email) {
        if (email == null) return null;
        if (trip.getUser().getEmail().equalsIgnoreCase(email)) return "OWNER";
        Optional<TripMember> member = memberRepository
                .findByTripIdAndUserEmailIgnoreCaseAndStatus(trip.getId(), email, "ACCEPTED");
        if (member.isEmpty()) return null;
        return "VIEWER".equals(member.get().getRole()) ? "VIEWER" : "EDITOR";
    }

    /** Chủ chuyến đi hoặc tripmate (kể cả chỉ xem) được xem dữ liệu nội bộ của chuyến đi. */
    @Transactional(readOnly = true)
    public Trip requireMember(Long tripId, String email) {
        Trip trip = loadTrip(tripId);
        if (roleOf(trip, email) == null) {
            throw new TripAccessException(HttpStatus.FORBIDDEN, "Bạn không phải thành viên của chuyến đi này!");
        }
        return trip;
    }

    /** Chủ chuyến đi hoặc tripmate được chỉnh sửa. */
    @Transactional(readOnly = true)
    public Trip requireEdit(Long tripId, String email) {
        return requireEdit(loadTrip(tripId), email);
    }

    @Transactional(readOnly = true)
    public Trip requireEdit(Trip trip, String email) {
        String role = roleOf(trip, email);
        if (role == null) {
            throw new TripAccessException(HttpStatus.FORBIDDEN, "Bạn không phải thành viên của chuyến đi này!");
        }
        if ("VIEWER".equals(role)) {
            throw new TripAccessException(HttpStatus.FORBIDDEN, "Bạn chỉ có quyền xem chuyến đi này, không thể chỉnh sửa.");
        }
        return trip;
    }

    @Transactional(readOnly = true)
    public Trip requireEditByDay(Long dayId, String email) {
        TripDay day = tripDayRepository.findById(dayId)
                .orElseThrow(() -> new TripAccessException(HttpStatus.NOT_FOUND, "Không tìm thấy ngày trong lịch trình!"));
        return requireEdit(day.getTrip(), email);
    }

    @Transactional(readOnly = true)
    public Trip requireEditByDestination(Long destinationId, String email) {
        TripDestination destination = tripDestinationRepository.findById(destinationId)
                .orElseThrow(() -> new TripAccessException(HttpStatus.NOT_FOUND, "Không tìm thấy địa điểm trong lịch trình!"));
        return requireEdit(destination.getTripDay().getTrip(), email);
    }

    private Trip loadTrip(Long tripId) {
        return tripRepository.findById(tripId)
                .orElseThrow(() -> new TripAccessException(HttpStatus.NOT_FOUND, "Không tìm thấy chuyến đi!"));
    }
}
