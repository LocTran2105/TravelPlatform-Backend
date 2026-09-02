package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.AlertRequest;
import hcmute.edu.vn.backend.dto.CustomPlaceRequest;
import hcmute.edu.vn.backend.service.MapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/map")
public class MapController {

    @Autowired
    private MapService mapService;

    // ================= CUSTOM PLACES =================
    @PostMapping("/places")
    public ResponseEntity<?> addCustomPlace(@RequestBody CustomPlaceRequest request, Principal principal) {
        try {
            return ResponseEntity.ok(mapService.addCustomPlace(request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    @GetMapping("/places/my-places")
    public ResponseEntity<?> getMyPlaces(Principal principal) {
        try {
            return ResponseEntity.ok(mapService.getMyPlaces(principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // ================= ALERTS =================
    @PostMapping("/alerts")
    public ResponseEntity<?> createAlert(@RequestBody AlertRequest request, Principal principal) {
        try {
            return ResponseEntity.ok(mapService.createAlert(request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API này không cần bảo mật (Ai cũng xem được cảnh báo chung)
    @GetMapping("/alerts/approved")
    public ResponseEntity<?> getApprovedAlerts() {
        return ResponseEntity.ok(mapService.getApprovedAlerts());
    }
}