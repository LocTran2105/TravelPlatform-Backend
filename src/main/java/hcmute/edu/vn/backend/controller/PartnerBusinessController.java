package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.PartnerBusinessRequest;
import hcmute.edu.vn.backend.service.PartnerBusinessService;
import hcmute.edu.vn.backend.service.PartnerSubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/partner/businesses")
@RequiredArgsConstructor
public class PartnerBusinessController {
    private final PartnerBusinessService businessService;
    private final PartnerSubmissionService submissionService;

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody PartnerBusinessRequest request, Principal principal) {
        try { return ResponseEntity.ok(businessService.create(request, principal.getName())); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }

    @GetMapping("/mine")
    public ResponseEntity<?> mine(Principal principal) {
        try { return ResponseEntity.ok(businessService.getMine(principal.getName())); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }

    @GetMapping("/{businessId}")
    public ResponseEntity<?> detail(@PathVariable Long businessId, Principal principal) {
        try { return ResponseEntity.ok(businessService.getMineById(businessId, principal.getName())); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }

    @PutMapping("/{businessId}")
    public ResponseEntity<?> update(@PathVariable Long businessId,
                                    @Valid @RequestBody PartnerBusinessRequest request,
                                    Principal principal) {
        try { return ResponseEntity.ok(businessService.update(businessId, request, principal.getName())); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }

    @DeleteMapping("/{businessId}")
    public ResponseEntity<?> delete(@PathVariable Long businessId, Principal principal) {
        try { businessService.softDelete(businessId, principal.getName()); return ResponseEntity.ok("Đã ẩn cơ sở kinh doanh."); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }

    @PostMapping("/{businessId}/submit")
    public ResponseEntity<?> submit(@PathVariable Long businessId, Principal principal) {
        try { return ResponseEntity.ok(submissionService.submitBusiness(businessId, principal.getName())); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }
}