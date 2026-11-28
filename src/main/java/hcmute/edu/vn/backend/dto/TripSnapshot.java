package hcmute.edu.vn.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bản chụp lịch trình (kèm chi phí) được lưu trong bài viết (cột posts.trip_snapshot, JSON).
 * Tác giả sửa chuyến đi gốc thì bản chụp KHÔNG đổi; người xem bài xem và sao chép từ bản chụp này.
 *
 * Ngày / giờ lưu dạng chuỗi ISO ("2026-12-01", "09:00") để JSON không phụ thuộc cấu hình Jackson.
 * Cố ý KHÔNG lưu: thành viên, người trả tiền, cách chia tiền, mô tả khoản chi.
 */
public record TripSnapshot(
        Integer version,
        String title,
        String destination,
        String startDate,
        String endDate,
        Integer memberCount,
        BigDecimal totalBudget,
        String coverImageUrl,
        String suggestedCoverUrl,
        Preference preference,
        List<Day> days,
        List<ExpenseItem> expenses
) {
    public static final int CURRENT_VERSION = 1;

    public record Preference(
            List<String> tags,
            String accommodation,
            String transport,
            String intensity
    ) {}

    public record Day(
            Integer dayNumber,
            String dayDate,
            List<Stop> stops
    ) {}

    /** key: số thứ tự duy nhất trong bản chụp, để khoản chi trỏ tới điểm đến. */
    public record Stop(
            Integer key,
            Long placeId,
            String placeName,
            String itemType,
            String startTime,
            String endTime,
            BigDecimal latitude,
            BigDecimal longitude,
            String note,
            BigDecimal estimatedCost,
            Integer orderIndex,
            String timeRange,
            String cost
    ) {}

    /** Khoản chi: chi cho mục nào (tên + hạng mục) và bao nhiêu. stopKey = null nghĩa là khoản chi chung. */
    public record ExpenseItem(
            String title,
            String category,
            BigDecimal amount,
            Integer stopKey
    ) {}

    /** Bản dùng để tính hash: bỏ ảnh bìa gợi ý (do hệ thống tính, không phải tác giả sửa). */
    public TripSnapshot forHash() {
        return new TripSnapshot(version, title, destination, startDate, endDate, memberCount, totalBudget,
                coverImageUrl, null, preference, days, expenses);
    }
}
