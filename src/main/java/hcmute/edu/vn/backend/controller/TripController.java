package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.BudgetRequest;
import hcmute.edu.vn.backend.dto.TripRequest;
import hcmute.edu.vn.backend.dto.TripVisibilityRequest;
import hcmute.edu.vn.backend.service.TripService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

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

    // API 3b: Cập nhật tổng ngân sách của chuyến đi
    @PatchMapping("/{tripId}/budget")
    public ResponseEntity<?> updateBudget(@PathVariable Long tripId, @RequestBody BudgetRequest request, Principal principal) {
        try {
            return ResponseEntity.ok(tripService.updateBudget(tripId, request.getTotalBudget(), principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API 3c: Danh sách chuyến đi của một người (trang cá nhân) - có áp dụng quyền hiển thị
    @GetMapping("/user/{username}")
    public ResponseEntity<?> getUserTrips(@PathVariable String username, Principal principal) {
        try {
            String viewerEmail = (principal != null) ? principal.getName() : null;
            return ResponseEntity.ok(tripService.getUserTrips(username, viewerEmail));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API 3d: Đổi quyền hiển thị của chuyến đi (PUBLIC / FOLLOWERS / FRIENDS / PRIVATE)
    @PatchMapping("/{tripId}/visibility")
    public ResponseEntity<?> updateVisibility(@PathVariable Long tripId, @RequestBody TripVisibilityRequest request, Principal principal) {
        try {
            String visibility = tripService.updateVisibility(tripId, request.getVisibility(), principal.getName());
            return ResponseEntity.ok(Map.of("visibility", visibility));
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

    // API 5: XÓA CHUYẾN ĐI
    @DeleteMapping("/{tripId}")
    public ResponseEntity<?> deleteTrip(@PathVariable Long tripId, Principal principal) {
        try {
            tripService.deleteTrip(tripId, principal.getName());
            return ResponseEntity.ok("Đã xóa chuyến đi");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }
    // API 6: Kiểm tra email bạn đồng hành đã đăng ký chưa
    @GetMapping("/check-email")
    public ResponseEntity<?> checkEmail(@RequestParam String email) {
        return ResponseEntity.ok(Map.of("exists", tripService.isEmailRegistered(email)));
    }
}