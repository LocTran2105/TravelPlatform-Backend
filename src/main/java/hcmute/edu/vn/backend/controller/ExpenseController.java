package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.ExpenseRequest;
import hcmute.edu.vn.backend.service.ExpenseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/trips/{tripId}/expenses")
public class ExpenseController {

    @Autowired
    private ExpenseService expenseService;

    // API: Thêm một khoản chi tiêu
    @PostMapping
    public ResponseEntity<?> addExpense(
            @PathVariable Long tripId,
            @RequestBody ExpenseRequest request,
            Principal principal) {
        try {
            return ResponseEntity.ok(expenseService.addExpense(tripId, request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API: Xem báo cáo chia tiền tự động
    @GetMapping("/settlement")
    public ResponseEntity<?> getSettlementReport(
            @PathVariable Long tripId,
            Principal principal) {
        try {
            return ResponseEntity.ok(expenseService.calculateDebts(tripId, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }
}