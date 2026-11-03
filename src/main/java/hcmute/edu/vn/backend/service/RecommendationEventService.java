package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.TrackingEventRequest;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.entity.RecommendationEvent;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.PlaceRepository;
import hcmute.edu.vn.backend.repository.RecommendationEventRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationEventService {

    private final RecommendationEventRepository eventRepository;
    private final UserRepository userRepository;
    private final TripRepository tripRepository;
    private final PlaceRepository placeRepository;

    @Transactional
    public void logEvent(TrackingEventRequest request) {
        if (request == null || request.getUserId() == null || request.getPlaceId() == null) {
            log.debug("Bỏ qua tracking event do thiếu userId hoặc placeId: {}", request);
            return;
        }

        User user = userRepository.findById(request.getUserId()).orElse(null);
        Place place = placeRepository.findById(request.getPlaceId()).orElse(null);
        if (user == null || place == null) {
            return;
        }

        Trip trip = null;
        if (request.getTripId() != null) {
            trip = tripRepository.findById(request.getTripId()).orElse(null);
        }

        RecommendationEvent event = RecommendationEvent.builder()
                .user(user)
                .trip(trip)
                .place(place)
                .eventType(request.getEventType())
                .recommendationScore(request.getRecommendationScore())
                .position(request.getPosition())
                .context(request.getContext())
                .build();

        eventRepository.save(event);
    }
}