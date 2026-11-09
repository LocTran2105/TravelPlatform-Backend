package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.BusinessHoursRequest;
import hcmute.edu.vn.backend.dto.SpecialHoursRequest;
import hcmute.edu.vn.backend.service.PartnerBusinessScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/partner/businesses/{businessId}")
@RequiredArgsConstructor
public class PartnerBusinessScheduleController {
    private final PartnerBusinessScheduleService service;

    @GetMapping("/hours")
    public ResponseEntity<?> getHours(@PathVariable Long businessId, Principal principal) {
        try { return ResponseEntity.ok(service.getHours(businessId, principal.getName())); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }

    @PutMapping("/hours")
    public ResponseEntity<?> replaceHours(@PathVariable Long businessId,
                                          @RequestBody List<@Valid BusinessHoursRequest> requests,
                                          Principal principal) {
        try { return ResponseEntity.ok(service.replaceHours(businessId, requests, principal.getName())); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }

    @GetMapping("/special-hours")
    public ResponseEntity<?> getSpecialHours(@PathVariable Long businessId,
                                             @RequestParam LocalDate from,
                                             @RequestParam LocalDate to,
                                             Principal principal) {
        try { return ResponseEntity.ok(service.getSpecialHours(businessId, from, to, principal.getName())); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }

    @PostMapping("/special-hours")
    public ResponseEntity<?> saveSpecialHours(@PathVariable Long businessId,
                                              @Valid @RequestBody SpecialHoursRequest request,
                                              Principal principal) {
        try { return ResponseEntity.ok(service.saveSpecialHour(businessId, request, principal.getName())); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }

    @DeleteMapping("/special-hours/{specialHourId}")
    public ResponseEntity<?> deleteSpecialHours(@PathVariable Long businessId,
                                                @PathVariable Long specialHourId,
                                                Principal principal) {
        try { service.deleteSpecialHour(businessId, specialHourId, principal.getName()); return ResponseEntity.ok("Đã xóa giờ hoạt động đặc biệt."); }
        catch (IllegalArgumentException | IllegalStateException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }
}