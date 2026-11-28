package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.ItineraryOptimizationDtos.ApplyRequest;
import hcmute.edu.vn.backend.dto.ItineraryOptimizationDtos.OptimizeRequest;
import hcmute.edu.vn.backend.service.TripAccessException;
import hcmute.edu.vn.backend.service.itinerary.ItineraryOptimizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * Tối ưu lộ trình: xem trước (thành viên) và áp dụng (người được chỉnh sửa).
 * POST /api/trips/{tripId}/itinerary/optimize, POST /api/trips/{tripId}/itinerary/optimize/apply
 */
@RestController
@RequestMapping("/api/trips/{tripId}/itinerary/optimize")
@RequiredArgsConstructor
public class ItineraryOptimizationController {

    private final ItineraryOptimizationService optimizationService;

    @PostMapping
    public ResponseEntity<?> optimize(@PathVariable Long tripId,
                                      @RequestBody(required = false) OptimizeRequest request,
                                      Principal principal) {
        return ResponseEntity.ok(optimizationService.optimize(tripId, request, principal.getName()));
    }

    @PostMapping("/apply")
    public ResponseEntity<?> apply(@PathVariable Long tripId, @RequestBody ApplyRequest request, Principal principal) {
        return ResponseEntity.ok(optimizationService.apply(tripId, request, principal.getName()));
    }

    @ExceptionHandler(TripAccessException.class)
    public ResponseEntity<String> handleAccess(TripAccessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
