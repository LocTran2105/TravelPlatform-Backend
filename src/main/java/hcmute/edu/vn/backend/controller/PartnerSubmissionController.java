package hcmute.edu.vn.backend.controller;
import hcmute.edu.vn.backend.service.PartnerSubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
@RestController
@RequestMapping("/api/partner/businesses/{businessId}/submissions")
@RequiredArgsConstructor
public class PartnerSubmissionController {
    private final PartnerSubmissionService service;
    @GetMapping public ResponseEntity<?> history(@PathVariable Long businessId,Principal p){try{return ResponseEntity.ok(service.history(businessId,p.getName()));}catch(IllegalArgumentException|IllegalStateException e){return ResponseEntity.badRequest().body(e.getMessage());}}
}