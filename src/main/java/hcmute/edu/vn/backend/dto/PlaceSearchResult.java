package hcmute.edu.vn.backend.dto;

import java.math.BigDecimal;

/**
 * Kết quả tìm địa điểm trong DB của hệ thống (ô "Thêm địa điểm" ở TripDetail).
 * verified = true khi Place (PARTNER hoặc OSM đã liên kết) thuộc một Business đã được Admin duyệt và đang công khai.
 * providerPlaceId (vd. "node/123" với OSM) giúp giao diện gộp kết quả Photon trùng với Place trong DB.
 */
public record PlaceSearchResult(
        Long id,
        String name,
        String address,
        String destination,
        String placeType,
        String provider,
        String providerPlaceId,
        BigDecimal latitude,
        BigDecimal longitude,
        boolean verified
) {
}