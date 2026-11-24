package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.service.PlaceCoverService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/places")
@RequiredArgsConstructor
public class PlaceMediaController {

    private final PlaceCoverService placeCoverService;

    /** Batch endpoint used by the AI recommendation cards and the Trip map panel. */
    @GetMapping("/covers")
    public ResponseEntity<Map<Long, String>> getCoverUrls(
            @RequestParam(name = "placeIds") List<Long> placeIds) {
        return ResponseEntity.ok(placeCoverService.getCoverUrls(placeIds));
    }
}
