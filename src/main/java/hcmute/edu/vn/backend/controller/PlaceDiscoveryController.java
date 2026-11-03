package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.PlaceDiscoveryDTO;
import hcmute.edu.vn.backend.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/places")
@RequiredArgsConstructor
@SuppressWarnings({"rawtypes", "unchecked"})
public class PlaceDiscoveryController {

    private final RecommendationService recommendationService;

    @GetMapping("/discover")
    public ResponseEntity discoverPlaces(
            @RequestParam(required = false) Long tripId,
            @RequestParam String destination,
            @RequestParam(defaultValue = "ATTRACTION") String category,
            @RequestParam(defaultValue = "8") int limit) {

        int safeLimit = Math.min(Math.max(limit, 1), 20);
        List<PlaceDiscoveryDTO> recommendations = recommendationService.discoverAndRankPlaces(
                tripId, destination, category, safeLimit);

        Map body = new LinkedHashMap();
        body.put("destination", destination);
        body.put("category", category);
        body.put("source", "OPENSTREETMAP");
        body.put("attribution", "© OpenStreetMap contributors");
        body.put("recommendations", recommendations);

        return ResponseEntity.ok(body);
    }
}