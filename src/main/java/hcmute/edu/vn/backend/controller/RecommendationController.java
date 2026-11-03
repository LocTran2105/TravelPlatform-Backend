
package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.PlaceRecommendationDTO;
import hcmute.edu.vn.backend.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping("/{tripId}/recommendations/places")
    public ResponseEntity<List<PlaceRecommendationDTO>>
    getPlaceRecommendations(@PathVariable Long tripId) {

        List<PlaceRecommendationDTO> recommendations =
                recommendationService.getRecommendationsForTrip(tripId);

        return ResponseEntity.ok(recommendations);
    }
}
