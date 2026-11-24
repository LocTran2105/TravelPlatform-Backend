package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.service.TripAccessException;
import hcmute.edu.vn.backend.service.TripAccessService;
import hcmute.edu.vn.backend.service.TripDayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/trips") // Vẫn giữ base URL này để URL API là /api/trips/{tripId}/days
public class TripDayController {

    @Autowired
    private TripDayService tripDayService;

    @Autowired
    private TripAccessService accessService;

    // API: Thêm một ngày (Day) vào chuyến đi
    @PostMapping("/{tripId}/days")
    public ResponseEntity<?> addDayToTrip(
            @PathVariable Long tripId,
            @RequestParam Integer dayNumber,
            @RequestParam(required = false) LocalDate dayDate,
            Principal principal) {
        try {
            accessService.requireEdit(tripId, principal.getName()); // người chỉ xem không được thêm ngày
            return ResponseEntity.ok(tripDayService.addDayToTrip(tripId, dayNumber, dayDate, principal.getName()));
        } catch (TripAccessException e) {
            return ResponseEntity.status(e.getStatus()).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }
}