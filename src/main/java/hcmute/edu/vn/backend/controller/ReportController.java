package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.ModerationDto.ReportRequest;
import hcmute.edu.vn.backend.service.ReportService;
import hcmute.edu.vn.backend.service.TooManyReportsException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;
import java.util.NoSuchElementException;

/** Người dùng báo cáo nội dung vi phạm. Cần đăng nhập. */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping
    public ResponseEntity<?> report(@RequestBody ReportRequest request, Principal principal) {
        try {
            reportService.createReport(principal.getName(), request);
            return ResponseEntity.ok(Map.of("message", "Cảm ơn bạn. Chúng tôi sẽ xem xét báo cáo sớm."));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(e.getMessage());   // đã báo cáo rồi
        } catch (TooManyReportsException e) {
            return ResponseEntity.status(429).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Lỗi gửi báo cáo: " + e.getMessage());
        }
    }
}
