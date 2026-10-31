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

    // API: Dữ liệu tab Ngân sách (danh sách chi phí + thành viên + người dùng hiện tại)
    @GetMapping
    public ResponseEntity<?> getExpenses(@PathVariable Long tripId, Principal principal) {
        try {
            return ResponseEntity.ok(expenseService.getOverview(tripId, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

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

    // API: Xoá một khoản chi tiêu
    @DeleteMapping("/{expenseId}")
    public ResponseEntity<?> deleteExpense(
            @PathVariable Long tripId,
            @PathVariable Long expenseId,
            Principal principal) {
        try {
            expenseService.deleteExpense(tripId, expenseId, principal.getName());
            return ResponseEntity.ok("Đã xóa khoản chi!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // API: Cân đối nhóm (ai nợ ai bao nhiêu)
    @GetMapping("/settlement")
    public ResponseEntity<?> getSettlementReport(
            @PathVariable Long tripId,
            Principal principal) {
        try {
            return ResponseEntity.ok(expenseService.calculateSettlement(tripId, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }
}