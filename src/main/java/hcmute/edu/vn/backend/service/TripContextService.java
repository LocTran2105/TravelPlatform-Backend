package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.TripContextDTO;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripDay;
import hcmute.edu.vn.backend.entity.TripDestination;
import hcmute.edu.vn.backend.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Loads the canonical trip context while the JPA session is open. */
@Service
@RequiredArgsConstructor
public class TripContextService {

    private final TripRepository tripRepository;
    private final TripAccessService tripAccessService;

    /**
     * Dùng cho trợ lý AI: chủ chuyến đi hoặc tripmate (kể cả chỉ xem) đều dùng được,
     * kể cả khi chuyến đi ở chế độ riêng tư. Người ngoài bị từ chối (TripAccessException 403).
     */
    @Transactional(readOnly = true)
    public TripContextDTO loadContextForMember(Long tripId, String email) {
        if (tripId == null || email == null) {
            throw new IllegalArgumentException("Thiếu thông tin chuyến đi hoặc người dùng.");
        }
        return toContext(tripAccessService.requireMember(tripId, email));
    }

    @Transactional(readOnly = true)
    public TripContextDTO loadAuthorizedContext(Long tripId, Long currentUserId) {
        if (tripId == null || currentUserId == null) {
            throw new IllegalArgumentException("Thiếu thông tin chuyến đi hoặc người dùng.");
        }

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến đi với ID: " + tripId));

        boolean isOwner = trip.getUser() != null
                && Objects.equals(trip.getUser().getId(), currentUserId);
        boolean isPublic = Boolean.TRUE.equals(trip.getIsPublic());
        if (!isOwner && !isPublic) {
            throw new AccessDeniedException("Bạn không có quyền truy cập chuyến đi này.");
        }
        return toContext(trip);
    }

    private TripContextDTO toContext(Trip trip) {

        List<TripContextDTO.TripDaySummaryDTO> daySummaries = new ArrayList<>();
        TripDestination anchor = null;
        if (trip.getTripDays() != null) {
            List<TripDay> sortedDays = new ArrayList<>(trip.getTripDays());
            sortedDays.sort(Comparator.comparing(
                    TripDay::getDayNumber,
                    Comparator.nullsLast(Comparator.naturalOrder())));

            for (TripDay day : sortedDays) {
                List<String> existingPlaces = new ArrayList<>();
                List<TripContextDTO.PlacePoint> placePoints = new ArrayList<>();
                if (day.getDestinations() != null) {
                    List<TripDestination> ordered = new ArrayList<>(day.getDestinations());
                    ordered.sort(Comparator.comparing(
                            TripDestination::getOrderIndex,
                            Comparator.nullsLast(Comparator.naturalOrder())));
                    for (TripDestination destination : ordered) {
                        if (destination != null
                                && destination.getLatitude() != null && destination.getLongitude() != null) {
                            if (anchor == null) anchor = destination;
                            placePoints.add(TripContextDTO.PlacePoint.builder()
                                    .name(destination.getPlaceName())
                                    .latitude(destination.getLatitude().doubleValue())
                                    .longitude(destination.getLongitude().doubleValue())
                                    .build());
                        }
                        if (destination != null && destination.getPlaceName() != null
                                && !destination.getPlaceName().isBlank()) {
                            existingPlaces.add(destination.getPlaceName());
                        }
                    }
                }

                daySummaries.add(TripContextDTO.TripDaySummaryDTO.builder()
                        .dayId(day.getId())
                        .dayNumber(day.getDayNumber())
                        .dayDate(day.getDayDate())
                        .existingPlaces(existingPlaces)
                        .placePoints(placePoints)
                        .build());
            }
        }

        return TripContextDTO.builder()
                .tripId(trip.getId())
                .title(trip.getTitle())
                .destination(trip.getDestination())
                .startDate(trip.getStartDate())
                .endDate(trip.getEndDate())
                .memberCount(trip.getMemberCount())
                .totalBudget(trip.getTotalBudget())
                .tripDays(daySummaries)
                .anchorName(anchor != null ? anchor.getPlaceName() : null)
                .anchorLatitude(anchor != null ? anchor.getLatitude().doubleValue() : null)
                .anchorLongitude(anchor != null ? anchor.getLongitude().doubleValue() : null)
                .build();
    }
}