package hcmute.edu.vn.backend.dto;

import java.math.BigDecimal;

/**
 * Kết quả tìm địa điểm trong DB của hệ thống (ô "Thêm địa điểm" ở TripDetail).
 * verified = true khi là Place PARTNER của Business đã được Admin duyệt và đang công khai.
 */
public record PlaceSearchResult(
        Long id,
        String name,
        String address,
        String destination,
        String placeType,
        String provider,
        BigDecimal latitude,
        BigDecimal longitude,
        boolean verified
) {
}