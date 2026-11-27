package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.PlaceSearchResult;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.repository.PlaceRepository;
import hcmute.edu.vn.backend.service.PlaceNameMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    private static final double SAME_PLACE_METERS = 100.0;

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
        // Lấy dư để sau khi gộp trùng vẫn đủ số kết quả.
        List<Place> places = new ArrayList<>(placeRepository.searchPublicPlaces(
                likePattern(q),
                likePattern(firstSegment),
                clean(destination),
                safeLimit * 2));

        // "Tùng cafe" không khớp LIKE '%Tùng cafe%' với "Cafe Tùng": tìm thêm theo từ khóa có nghĩa dài nhất,
        // rồi giữ Place có tên/địa chỉ chứa đủ mọi từ khóa (không phân biệt dấu, thứ tự, từ chung như "cafe").
        List<String> tokens = List.of(PlaceNameMatcher.normalize(q).split(" ")).stream()
                .filter(t -> t.length() >= 2).toList();
        if (q.contains(" ") && !tokens.isEmpty() && places.size() < safeLimit * 2) {
            String longest = tokens.stream().max((a, b) -> Integer.compare(a.length(), b.length())).orElseThrow();
            Set<Long> seen = places.stream().map(Place::getId).collect(Collectors.toSet());
            for (Place place : placeRepository.searchPublicPlaces(
                    likePattern(longest), likePattern(longest), clean(destination), 30)) {
                String haystack = PlaceNameMatcher.normalize(place.getName() + " " + place.getAddress());
                if (!seen.contains(place.getId()) && tokens.stream().allMatch(haystack::contains)) {
                    places.add(place);
                    seen.add(place.getId());
                }
            }
        }

        Set<Long> businessIds = places.stream()
                .map(Place::getPartnerBusinessId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> publicBusinessIds = businessIds.isEmpty() ? Set.of()
                : placeRepository.findPublicBusinessIds(businessIds).stream()
                        .map(Number::longValue)
                        .collect(Collectors.toSet());

        // Đã xác minh lên trước (sort ổn định giữ thứ tự liên quan của SQL), rồi gộp trùng: bản đã xác minh được giữ.
        List<PlaceSearchResult> candidates = places.stream()
                .map(place -> toResult(place, place.getPartnerBusinessId() != null
                        && publicBusinessIds.contains(place.getPartnerBusinessId())))
                .sorted((a, b) -> Boolean.compare(b.verified(), a.verified()))
                .toList();
        List<PlaceSearchResult> results = new ArrayList<>();
        for (PlaceSearchResult result : candidates) {
            if (results.stream().noneMatch(kept -> isSamePlace(kept, result))) results.add(result);
            if (results.size() >= safeLimit) break;
        }
        return ResponseEntity.ok(results);
    }

    /**
     * Hai kết quả là một địa điểm khi tên gần giống và cách nhau dưới 100 m
     * (vd. OSM có cả "Cafe Tùng" và "Tùng Coffee" cho cùng một quán). Kết quả đã xác minh đứng trước nên được giữ.
     */
    private static boolean isSamePlace(PlaceSearchResult a, PlaceSearchResult b) {
        if (a.latitude() == null || a.longitude() == null || b.latitude() == null || b.longitude() == null) return false;
        if (!PlaceNameMatcher.similar(a.name(), b.name())) return false;
        return PlaceNameMatcher.distanceMeters(
                a.latitude().doubleValue(), a.longitude().doubleValue(),
                b.latitude().doubleValue(), b.longitude().doubleValue()) <= SAME_PLACE_METERS;
    }

    private PlaceSearchResult toResult(Place place, boolean verified) {
        return new PlaceSearchResult(
                place.getId(),
                place.getName(),
                place.getAddress(),
                place.getDestination(),
                place.getPlaceType(),
                place.getProvider() == null ? null : place.getProvider().toUpperCase(Locale.ROOT),
                place.getProviderPlaceId(),
                place.getLatitude(),
                place.getLongitude(),
                verified);
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