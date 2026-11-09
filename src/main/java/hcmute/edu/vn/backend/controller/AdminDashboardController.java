package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.service.AdminDashboardService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    /**
     * Thống kê tổng quan cho Admin.
     */
    @GetMapping
    public ResponseEntity<?> getDashboard() {

        return ResponseEntity.ok(
                dashboardService.getDashboard()
        );
    }
}