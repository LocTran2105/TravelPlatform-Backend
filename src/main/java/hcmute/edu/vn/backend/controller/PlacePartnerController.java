package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.PartnerPlaceProfileResponse;
import hcmute.edu.vn.backend.dto.PartnerPlaceProfileResponse.HoursSlot;
import hcmute.edu.vn.backend.service.PartnerPlaceProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Dữ liệu Partner đã xác minh cho du khách (panel địa điểm, cảnh báo giờ mở cửa trong lịch trình). */
@RestController
@RequestMapping("/api/v1/places")
@RequiredArgsConstructor
public class PlacePartnerController {

    private final PartnerPlaceProfileService profileService;

    /** 204 khi Place không có Partner công khai: phía giao diện dùng nguồn khác (Wikipedia). */
    @GetMapping("/{placeId}/partner-profile")
    public ResponseEntity<PartnerPlaceProfileResponse> getPartnerProfile(@PathVariable Long placeId) {
        return profileService.getProfile(placeId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Giờ mở cửa của nhiều Place trong một request (tối đa 100 ID). */
    @GetMapping("/partner-hours")
    public ResponseEntity<Map<Long, List<HoursSlot>>> getPartnerHours(
            @RequestParam(name = "placeIds") List<Long> placeIds) {
        return ResponseEntity.ok(profileService.getHours(placeIds));
    }
}
