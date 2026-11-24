package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.service.CoverImageService;
import hcmute.edu.vn.backend.service.TripAccessException;
import hcmute.edu.vn.backend.service.TripCoverService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CoverImageController {

    private final CoverImageService coverImageService;
    private final TripCoverService tripCoverService;

    public CoverImageController(CoverImageService coverImageService, TripCoverService tripCoverService) {
        this.coverImageService = coverImageService;
        this.tripCoverService = tripCoverService;
    }

    /** Thư viện ảnh bìa của hệ thống (cần đăng nhập). */
    @GetMapping("/cover-images")
    public ResponseEntity<?> library() {
        return ResponseEntity.ok(coverImageService.listActive());
    }

    /** Chọn ảnh từ thư viện làm ảnh bìa. Body: { "coverImageId": 3 } */
    @PutMapping("/trips/{tripId}/cover")
    public ResponseEntity<?> choose(@PathVariable Long tripId,
                                    @RequestBody Map<String, Object> body,
                                    Principal principal) {
        try {
            Long imageId = null;
            Object raw = body == null ? null : body.get("coverImageId");
            if (raw instanceof Number n) imageId = n.longValue();
            else if (raw instanceof String s && !s.isBlank()) imageId = Long.valueOf(s.trim());
            return ResponseEntity.ok(tripCoverService.setFromLibrary(tripId, imageId, principal.getName()));
        } catch (TripAccessException e) {
            return ResponseEntity.status(e.getStatus()).body(e.getMessage());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body("Mã ảnh không hợp lệ.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    /** Tải ảnh riêng lên làm ảnh bìa. multipart, field "file". */
    @PostMapping(value = "/trips/{tripId}/cover/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@PathVariable Long tripId,
                                    @RequestParam("file") MultipartFile file,
                                    Principal principal) {
        try {
            return ResponseEntity.ok(tripCoverService.upload(tripId, file, principal.getName()));
        } catch (TripAccessException e) {
            return ResponseEntity.status(e.getStatus()).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    /** Gỡ ảnh bìa riêng (quay về ảnh gợi ý tự động). */
    @DeleteMapping("/trips/{tripId}/cover")
    public ResponseEntity<?> remove(@PathVariable Long tripId, Principal principal) {
        try {
            return ResponseEntity.ok(tripCoverService.remove(tripId, principal.getName()));
        } catch (TripAccessException e) {
            return ResponseEntity.status(e.getStatus()).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }
}
