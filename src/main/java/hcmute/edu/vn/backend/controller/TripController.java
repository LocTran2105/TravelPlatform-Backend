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

    // API: Tạo chuyến đi mới
    @PostMapping
    public ResponseEntity<?> createTrip(@RequestBody TripRequest request, Principal principal) {
        try {
            // principal.getName() chính là hàm để lấy ra Email của User đang đăng nhập
            String currentUserEmail = principal.getName();
            return ResponseEntity.ok(tripService.createTrip(request, currentUserEmail));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API: Lấy danh sách chuyến đi của tôi
    @GetMapping("/my-trips")
    public ResponseEntity<?> getMyTrips(Principal principal) {
        try {
            String currentUserEmail = principal.getName();
            return ResponseEntity.ok(tripService.getMyTrips(currentUserEmail));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API 3: Lấy chi tiết chuyến đi
    @GetMapping("/{tripId}")
    public ResponseEntity<?> getTripDetails(@PathVariable Long tripId, java.security.Principal principal) {
        try {
            String currentUserEmail = principal.getName();
            return ResponseEntity.ok(tripService.getTripDetails(tripId, currentUserEmail));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API 4: Thêm một ngày (Day) vào chuyến đi
    @PostMapping("/{tripId}/days")
    public ResponseEntity<?> addDayToTrip(
            @PathVariable Long tripId,
            @RequestParam Integer dayNumber,
            @RequestParam(required = false) java.time.LocalDate dayDate,
            java.security.Principal principal) {
        try {
            return ResponseEntity.ok(tripService.addDayToTrip(tripId, dayNumber, dayDate, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API 5: Thêm điểm đến (Destination) vào Ngày
    @PostMapping("/days/{dayId}/destinations")
    public ResponseEntity<?> addDestinationToDay(
            @PathVariable Long dayId,
            @RequestBody hcmute.edu.vn.backend.dto.DestinationRequest request,
            java.security.Principal principal) {
        try {
            return ResponseEntity.ok(tripService.addDestinationToDay(dayId, request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }
}