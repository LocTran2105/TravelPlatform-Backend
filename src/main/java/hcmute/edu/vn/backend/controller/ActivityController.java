package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.service.TripAccessException;
import hcmute.edu.vn.backend.service.TripActivityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/** Lịch sử thao tác chuyến đi. Lỗi trả về là chuỗi thông báo (khớp cách frontend đọc error.response.data). */
@RestController
@RequestMapping("/api/trips/{tripId}/activities")
public class ActivityController {

    private static final Logger log = LoggerFactory.getLogger(ActivityController.class);

    private final TripActivityService activityService;

    public ActivityController(TripActivityService activityService) {
        this.activityService = activityService;
    }

    /** GET /api/trips/{id}/activities?size=20&before=123 : mới nhất trước, before = id dòng cuối của trang trước. */
    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long tripId,
                                  @RequestParam(required = false) Long before,
                                  @RequestParam(required = false) Integer size,
                                  Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Vui lòng đăng nhập.");
        }
        try {
            return ResponseEntity.ok(activityService.list(tripId, principal.getName(), before, size));
        } catch (TripAccessException e) {
            return ResponseEntity.status(e.getStatus()).body(e.getMessage());
        } catch (Exception e) {
            log.error("Lỗi đọc lịch sử thao tác", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Có lỗi xảy ra, vui lòng thử lại.");
        }
    }
}
