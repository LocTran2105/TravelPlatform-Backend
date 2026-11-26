package hcmute.edu.vn.backend.dto;

import java.util.List;

/**
 * Thứ tự mới của lịch trình sau khi kéo thả. Chỉ cần gửi các NGÀY bị thay đổi
 * (kéo trong một ngày: 1 ngày; kéo sang ngày khác: ngày cũ + ngày mới).
 */
public record ItineraryOrderRequest(List<DayOrder> days) {

    /** destinationIds theo đúng thứ tự hiển thị mới của ngày đó. */
    public record DayOrder(Long dayId, List<Long> destinationIds) { }
}