package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.PlaceSearchResult;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Tìm địa điểm ĐÃ CÓ trong hệ thống theo tên hoặc địa chỉ.
 * Bổ sung cho Photon/OSM ở FE: địa điểm Partner do Admin xác nhận (OSM không có) chỉ nằm trong DB,
 * nên tìm bằng Photon sẽ không bao giờ ra.
 */
@RestController
@RequestMapping("/api/v1/places")
@RequiredArgsConstructor
public class PlaceSearchController {

    private static final int MIN_QUERY_LENGTH = 2;
    private static final int MAX_QUERY_LENGTH = 120;
    private static final int MAX_LIMIT = 10;

    private final PlaceRepository placeRepository;

    @GetMapping("/search")
    public ResponseEntity<List<PlaceSearchResult>> search(
            @RequestParam("q") String query,
            @RequestParam(name = "destination", required = false) String destination,
            @RequestParam(name = "limit", defaultValue = "6") int limit) {

        String q = clean(query);
        if (q.length() < MIN_QUERY_LENGTH) {
            return ResponseEntity.ok(List.of());
        }

        // Người dùng hay dán cả địa chỉ dài "Hẻm 31/8 - Đường 3/4 - Phường 3, Đà Lạt, Việt Nam":
        // tìm thêm theo đoạn đầu (trước dấu phẩy) để vẫn khớp khi phần đuôi viết khác DB.
        String firstSegment = clean(q.split(",")[0]);
        if (firstSegment.length() < MIN_QUERY_LENGTH) firstSegment = q;

        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Place> places = placeRepository.searchPublicPlaces(
                likePattern(q),
                likePattern(firstSegment),
                clean(destination),
                safeLimit);

        return ResponseEntity.ok(places.stream().map(this::toResult).toList());
    }

    private PlaceSearchResult toResult(Place place) {
        boolean partner = "PARTNER".equalsIgnoreCase(place.getProvider());
        return new PlaceSearchResult(
                place.getId(),
                place.getName(),
                place.getAddress(),
                place.getDestination(),
                place.getPlaceType(),
                place.getProvider() == null ? null : place.getProvider().toUpperCase(Locale.ROOT),
                place.getLatitude(),
                place.getLongitude(),
                // Query chỉ trả PARTNER khi Business đã duyệt + công khai + đang mở.
                partner);
    }

    private static String clean(String value) {
        if (value == null) return "";
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.length() > MAX_QUERY_LENGTH ? trimmed.substring(0, MAX_QUERY_LENGTH) : trimmed;
    }

    /** Escape ký tự đặc biệt của LIKE để người dùng gõ "%" hay "_" không thành wildcard. */
    private static String likePattern(String value) {
        String escaped = value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}