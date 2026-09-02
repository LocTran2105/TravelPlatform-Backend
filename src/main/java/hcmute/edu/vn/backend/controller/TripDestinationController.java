package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.DestinationRequest;
import hcmute.edu.vn.backend.service.TripDestinationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/trips/days") // Base URL trỏ thẳng vào các Day
public class TripDestinationController {

    @Autowired
    private TripDestinationService tripDestinationService;

    // API: Thêm điểm đến (Destination) vào một Ngày cụ thể
    @PostMapping("/{dayId}/destinations")
    public ResponseEntity<?> addDestinationToDay(
            @PathVariable Long dayId,
            @RequestBody DestinationRequest request,
            Principal principal) {
        try {
            return ResponseEntity.ok(tripDestinationService.addDestinationToDay(dayId, request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }
}