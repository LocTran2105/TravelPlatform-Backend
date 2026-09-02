package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.TripRequest;
import hcmute.edu.vn.backend.service.TripService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/trips")
public class TripController {

    @Autowired
    private TripService tripService;

    // API 1: Tạo chuyến đi mới
    @PostMapping
    public ResponseEntity<?> createTrip(@RequestBody TripRequest request, Principal principal) {
        try {
            return ResponseEntity.ok(tripService.createTrip(request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API 2: Lấy danh sách chuyến đi của tôi
    @GetMapping("/my-trips")
    public ResponseEntity<?> getMyTrips(Principal principal) {
        try {
            return ResponseEntity.ok(tripService.getMyTrips(principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API 3: Lấy chi tiết chuyến đi
    @GetMapping("/{tripId}")
    public ResponseEntity<?> getTripDetails(@PathVariable Long tripId, Principal principal) {
        try {
            return ResponseEntity.ok(tripService.getTripDetails(tripId, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API 4: NHÂN BẢN (CLONE) LỊCH TRÌNH
    @PostMapping("/{originalTripId}/clone")
    public ResponseEntity<?> cloneTrip(@PathVariable Long originalTripId, Principal principal) {
        try {
            return ResponseEntity.ok(tripService.cloneTrip(originalTripId, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }
}