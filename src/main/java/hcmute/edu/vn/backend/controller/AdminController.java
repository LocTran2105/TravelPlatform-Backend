package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.service.AdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private AdminService adminService;

    // API: Phê duyệt cảnh báo trên bản đồ
    @PutMapping("/alerts/{alertId}/approve")
    public ResponseEntity<?> approveAlert(@PathVariable Long alertId, @RequestParam boolean isApproved) {
        try {
            return ResponseEntity.ok(adminService.processAlert(alertId, isApproved));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API: Lấy danh sách báo cáo vi phạm
    @GetMapping("/reports")
    public ResponseEntity<?> getPendingReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(adminService.getPendingReports(page, size));
    }

    // API: Xử lý báo cáo
    @PutMapping("/reports/{reportId}/resolve")
    public ResponseEntity<?> resolveReport(@PathVariable Long reportId, @RequestParam String action) {
        try {
            return ResponseEntity.ok(adminService.resolveReport(reportId, action));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }
}