package hcmute.edu.vn.backend.service.itinerary;

import java.util.List;

/**
 * Mục đích ghé thăm một điểm và khung giờ đẹp nhất cho mục đích đó.
 *
 * @param purpose          mã mục đích (CLOUD_HUNT, SUNRISE, SUNSET, PHOTO, MEAL...)
 * @param label            nhãn tiếng Việt hiển thị cho người dùng
 * @param dwellMinutes     thời gian ở lại dự kiến
 * @param preferred        khung giờ ưa thích (ràng buộc mềm); rỗng = linh hoạt
 * @param weight           phạt mỗi phút lệch khỏi khung ưa thích
 * @param waitForPreferred đến sớm thì chờ tới đầu khung ưa thích (bình minh, hoàng hôn, bữa ăn...)
 * @param reason           lý do hiển thị khi xếp đúng khung ưa thích
 * @param defaultHours     giờ mở cửa ước tính khi địa điểm không khai báo
 */
public record VisitProfile(
        String purpose,
        String label,
        int dwellMinutes,
        List<TimeWindow> preferred,
        double weight,
        boolean waitForPreferred,
        String reason,
        List<TimeWindow> defaultHours) {

    public VisitProfile withDwell(int minutes) {
        return new VisitProfile(purpose, label, minutes, preferred, weight, waitForPreferred, reason, defaultHours);
    }

    public boolean flexible() {
        return preferred == null || preferred.isEmpty() || weight <= 0;
    }
}
