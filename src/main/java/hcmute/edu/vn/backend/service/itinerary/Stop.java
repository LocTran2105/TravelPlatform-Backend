package hcmute.edu.vn.backend.service.itinerary;

import java.util.List;

/**
 * Một điểm cần ghé trong ngày, đã gắn đủ ràng buộc cho bộ tối ưu.
 *
 * @param id           id TripDestination
 * @param openWindows  giờ mở cửa của NGÀY đó; rỗng = đóng cửa cả ngày
 * @param hoursKnown   true = giờ do Partner khai báo (ràng buộc cứng); false = giờ ước tính (ràng buộc mềm)
 * @param fixedStart   giờ hẹn người dùng đã đặt (phút); null = linh hoạt
 */
public record Stop(
        Long id,
        String name,
        int dwellMinutes,
        List<TimeWindow> openWindows,
        boolean hoursKnown,
        Integer fixedStart,
        VisitProfile profile) {

    public boolean closedAllDay() {
        return hoursKnown && (openWindows == null || openWindows.isEmpty());
    }
}
