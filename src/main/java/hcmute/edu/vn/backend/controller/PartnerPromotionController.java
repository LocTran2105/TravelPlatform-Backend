package hcmute.edu.vn.backend.controller;
import hcmute.edu.vn.backend.dto.PromotionRequest;
import hcmute.edu.vn.backend.service.PartnerPromotionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
@RestController
@RequestMapping("/api/partner/businesses/{businessId}/promotions")
@RequiredArgsConstructor
public class PartnerPromotionController {
    private final PartnerPromotionService service;
    @GetMapping public ResponseEntity<?> list(@PathVariable Long businessId,Principal p){try{return ResponseEntity.ok(service.list(businessId,p.getName()));}catch(IllegalArgumentException|IllegalStateException e){return ResponseEntity.badRequest().body(e.getMessage());}}
    @PostMapping public ResponseEntity<?> create(@PathVariable Long businessId,@Valid @RequestBody PromotionRequest r,Principal p){try{return ResponseEntity.ok(service.create(businessId,r,p.getName()));}catch(IllegalArgumentException|IllegalStateException e){return ResponseEntity.badRequest().body(e.getMessage());}}
    @PutMapping("/{promotionId}") public ResponseEntity<?> update(@PathVariable Long businessId,@PathVariable Long promotionId,@Valid @RequestBody PromotionRequest r,Principal p){try{return ResponseEntity.ok(service.update(businessId,promotionId,r,p.getName()));}catch(IllegalArgumentException|IllegalStateException e){return ResponseEntity.badRequest().body(e.getMessage());}}
    @DeleteMapping("/{promotionId}") public ResponseEntity<?> delete(@PathVariable Long businessId,@PathVariable Long promotionId,Principal p){try{service.deactivate(businessId,promotionId,p.getName());return ResponseEntity.ok("Đã ngừng khuyến mãi.");}catch(IllegalArgumentException|IllegalStateException e){return ResponseEntity.badRequest().body(e.getMessage());}}
}