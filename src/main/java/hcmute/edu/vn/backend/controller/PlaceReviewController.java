package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.service.PlaceReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/place-reviews")
public class PlaceReviewController {

    @Autowired
    private PlaceReviewService placeReviewService;

    @PostMapping
    public ResponseEntity<?> addOrUpdateReview(@RequestParam String locationName, @RequestParam Integer rating, @RequestParam String content, Principal principal) {
        try {
            return ResponseEntity.ok(placeReviewService.addOrUpdateReview(locationName, rating, content, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi gửi đánh giá: " + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<?> getReviews(@RequestParam String locationName, Principal principal) {
        String email = principal != null ? principal.getName() : null;
        return ResponseEntity.ok(placeReviewService.getReviews(locationName, email));
    }

    @PostMapping("/{reviewId}/like")
    public ResponseEntity<?> toggleLike(@PathVariable Long reviewId, Principal principal) {
        try {
            return ResponseEntity.ok(placeReviewService.toggleLike(reviewId, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}