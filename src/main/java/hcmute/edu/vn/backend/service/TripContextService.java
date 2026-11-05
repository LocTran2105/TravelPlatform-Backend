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

        List<TripContextDTO.TripDaySummaryDTO> daySummaries = new ArrayList<>();
        if (trip.getTripDays() != null) {
            List<TripDay> sortedDays = new ArrayList<>(trip.getTripDays());
            sortedDays.sort(Comparator.comparing(
                    TripDay::getDayNumber,
                    Comparator.nullsLast(Comparator.naturalOrder())));

            for (TripDay day : sortedDays) {
                List<String> existingPlaces = new ArrayList<>();
                if (day.getDestinations() != null) {
                    for (TripDestination destination : day.getDestinations()) {
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
                .build();
    }
}