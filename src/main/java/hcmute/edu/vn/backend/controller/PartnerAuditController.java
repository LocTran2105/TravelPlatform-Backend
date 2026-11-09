package hcmute.edu.vn.backend.controller;
import hcmute.edu.vn.backend.service.PartnerAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
@RestController
@RequestMapping("/api/partner/businesses/{businessId}/audit-logs")
@RequiredArgsConstructor
public class PartnerAuditController {
    private final PartnerAuditService service;
    @GetMapping public ResponseEntity<?> logs(@PathVariable Long businessId, Principal principal) {
        try { return ResponseEntity.ok(service.getBusinessLogs(businessId, principal.getName())); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }
}