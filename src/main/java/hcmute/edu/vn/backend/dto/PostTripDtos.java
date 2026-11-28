package hcmute.edu.vn.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO cho lịch trình trong bài viết. Mọi dữ liệu lấy từ BẢN CHỤP (posts.trip_snapshot),
 * không đọc chuyến đi gốc, nên tác giả sửa chuyến đi gốc thì bài không đổi.
 * Không có: thành viên, người trả tiền, cách chia tiền, mô tả khoản chi.
 */
public final class PostTripDtos {

    private PostTripDtos() {}

    /**
     * Thẻ lịch trình hiển thị trong bài (bảng tin).
     *
     * @param snapshotAt      lúc chụp (UTC)
     * @param sourceAvailable chỉ có giá trị khi người xem là TÁC GIẢ: chuyến đi gốc còn tồn tại không
     * @param sourceChanged   chỉ có giá trị khi người xem là TÁC GIẢ: chuyến gốc đã khác bản chụp
     */
    public record TripPreview(
            String title,
            String destination,
            String startDate,
            String endDate,
            Integer dayCount,
            Integer placeCount,
            Integer memberCount,
            BigDecimal totalBudget,
            BigDecimal totalSpent,
            BigDecimal perPerson,
            Long copyCount,
            String coverImageUrl,
            String suggestedCoverUrl,
            LocalDateTime snapshotAt,
            Boolean sourceAvailable,
            Boolean sourceChanged
    ) {}

    /** Lịch trình đầy đủ (chỉ đọc) để xem trước khi sao chép. */
    public record ItineraryPreview(
            TripPreview trip,
            List<DayPreview> days,
            List<CategoryTotal> costByCategory,
            List<ExpenseLine> generalExpenses
    ) {}

    public record DayPreview(
            Integer dayNumber,
            String dayDate,
            List<StopPreview> destinations
    ) {}

    /** estimatedCost: chi phí dự kiến; actualCost: tổng đã chi thực tế cho điểm đến này (null = chưa chi). */
    public record StopPreview(
            String placeName,
            String itemType,
            String startTime,
            String endTime,
            String note,
            BigDecimal estimatedCost,
            BigDecimal actualCost,
            List<ExpenseLine> expenses
    ) {}

    /** Chi cho mục nào (tên + hạng mục) và bao nhiêu. */
    public record ExpenseLine(
            String title,
            String category,
            BigDecimal amount
    ) {}

    public record CategoryTotal(
            String category,
            BigDecimal amount
    ) {}

    /** Người sao chép tự đặt tên và ngày bắt đầu mới (đều không bắt buộc). */
    public record CopyTripRequest(
            String title,
            LocalDate startDate
    ) {}

    public record CopyTripResponse(
            Long tripId,
            String title
    ) {}
}
