package hcmute.edu.vn.backend.controller;
import hcmute.edu.vn.backend.service.PartnerDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
@RestController
@RequestMapping("/api/partner/dashboard")
@RequiredArgsConstructor
public class PartnerDashboardController {
    private final PartnerDashboardService service;
    @GetMapping public ResponseEntity<?> dashboard(Principal principal) {
        try { return ResponseEntity.ok(service.getDashboard(principal.getName())); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }
}