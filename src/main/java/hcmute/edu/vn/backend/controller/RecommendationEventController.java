package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.TrackingEventRequest;
import hcmute.edu.vn.backend.service.RecommendationEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/recommendation-events")
@RequiredArgsConstructor
public class RecommendationEventController {

    private final RecommendationEventService eventService;

    @PostMapping
    public ResponseEntity<?> trackEvent(@RequestBody TrackingEventRequest request) {
        eventService.logEvent(request);
        return ResponseEntity.ok().build();
    }
}