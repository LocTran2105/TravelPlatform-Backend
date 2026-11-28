package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.TripStayDtos.StayRequest;
import hcmute.edu.vn.backend.service.TripAccessException;
import hcmute.edu.vn.backend.service.TripStayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/** Mục "Nơi ở" của chuyến đi: /api/trips/{tripId}/stays */
@RestController
@RequestMapping("/api/trips/{tripId}/stays")
@RequiredArgsConstructor
public class TripStayController {

    private final TripStayService stayService;

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long tripId, Principal principal) {
        return ResponseEntity.ok(stayService.list(tripId, principal.getName()));
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable Long tripId, @RequestBody StayRequest request, Principal principal) {
        return ResponseEntity.ok(stayService.create(tripId, request, principal.getName()));
    }

    @PutMapping("/{stayId}")
    public ResponseEntity<?> update(@PathVariable Long tripId, @PathVariable Long stayId,
                                    @RequestBody StayRequest request, Principal principal) {
        return ResponseEntity.ok(stayService.update(tripId, stayId, request, principal.getName()));
    }

    @DeleteMapping("/{stayId}")
    public ResponseEntity<?> delete(@PathVariable Long tripId, @PathVariable Long stayId, Principal principal) {
        stayService.delete(tripId, stayId, principal.getName());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(TripAccessException.class)
    public ResponseEntity<String> handleAccess(TripAccessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ex.getMessage());
    }
}
