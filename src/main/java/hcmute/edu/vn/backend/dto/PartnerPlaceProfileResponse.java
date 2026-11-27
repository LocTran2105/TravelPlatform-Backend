package hcmute.edu.vn.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Thông tin công khai của một Place đã được Partner xác minh (Business đã duyệt + đang công khai).
 * Dùng cho panel thông tin địa điểm phía du khách.
 */
public record PartnerPlaceProfileResponse(
        Long placeId,
        Long businessId,
        String name,
        String businessType,
        String shortDescription,
        String description,
        String address,
        String ward,
        String city,
        String province,
        BigDecimal latitude,
        BigDecimal longitude,
        String phoneNumber,
        String email,
        String websiteUrl,
        String facebookUrl,
        String instagramUrl,
        Integer priceLevel,
        String operatingStatus,
        /** true/false theo giờ Việt Nam; null khi Partner chưa khai báo giờ mở cửa. */
        Boolean openNow,
        List<HoursSlot> hours,
        List<Note> notes,
        List<Media> media
) {

    /** dayOfWeek: 1 = Thứ 2 ... 7 = Chủ nhật. openTime/closeTime dạng HH:mm. */
    public record HoursSlot(
            Integer dayOfWeek,
            String openTime,
            String closeTime,
            boolean closed,
            boolean closesNextDay
    ) { }

    public record Note(String title, String content, String noteType) { }

    public record Media(String url, String thumbnailUrl, String caption, boolean cover) { }
}
