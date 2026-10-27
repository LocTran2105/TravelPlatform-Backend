package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.CommentRequest;
import hcmute.edu.vn.backend.dto.ReviewRequest;
import hcmute.edu.vn.backend.service.TripCommentService;
import hcmute.edu.vn.backend.service.TripReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/trips/{tripId}")
public class TripSocialController {

    @Autowired
    private TripReviewService tripReviewService;

    @Autowired
    private TripCommentService tripCommentService;

    // ============ REVIEWS ============
    @PostMapping("/reviews")
    public ResponseEntity<?> addReview(
            @PathVariable Long tripId,
            @RequestBody ReviewRequest request,
            Principal principal) {
        try {
            // FIX 1: Đổi addReview thành addOrUpdateReview
            return ResponseEntity.ok(tripReviewService.addOrUpdateReview(tripId, request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    @GetMapping("/reviews")
    public ResponseEntity<?> getReviews(
            @PathVariable Long tripId,
            // FIX 2: Thêm tham số phân trang (page, size) mặc định
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size) {
        try {
            // Cập nhật truyền page và size vào service
            return ResponseEntity.ok(tripReviewService.getReviewsByTrip(tripId, page, size));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    // ============ COMMENTS ============
    @PostMapping("/comments")
    public ResponseEntity<?> addComment(
            @PathVariable Long tripId,
            @RequestBody CommentRequest request,
            Principal principal) {
        try {
            return ResponseEntity.ok(tripCommentService.addComment(tripId, request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    @GetMapping("/comments")
    public ResponseEntity<?> getComments(@PathVariable Long tripId) {
        return ResponseEntity.ok(tripCommentService.getCommentsByTrip(tripId));
    }
}