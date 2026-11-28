package hcmute.edu.vn.backend.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** DTO của chức năng tối ưu lộ trình. */
public final class ItineraryOptimizationDtos {

    private ItineraryOptimizationDtos() {}

    /**
     * Body của POST /api/trips/{tripId}/itinerary/optimize.
     * dayIds rỗng = cả chuyến. transport: MOTORBIKE/CAR/WALK. intensity: RELAX/BALANCED/PACKED.
     */
    public record OptimizeRequest(List<Long> dayIds, String transport, String intensity) {}

    public record OptimizationResponse(
            Long tripId,
            String signature,
            String transport,
            String intensity,
            boolean googleMaps,
            List<DayPlan> days,
            List<MoveSuggestion> moveSuggestions) {}

    /** Điểm xuất phát/kết thúc của ngày (nơi ở). kind: STAY hoặc ACCOMMODATION (điểm lưu trú cũ trong ngày). */
    public record Anchor(String name, Double latitude, Double longitude, String kind) {}

    public record Totals(double distanceKm, int travelMinutes, int issues) {}

    public record PlannedStop(
            Long destinationId,
            String name,
            Double latitude,
            Double longitude,
            Integer previousPosition,
            String arrive,
            String start,
            String depart,
            String currentTime,
            String suggestedTime,
            boolean fixedTime,
            double distanceKmFromPrev,
            int travelMinutesFromPrev,
            int waitMinutes,
            String purpose,
            String purposeLabel,
            String hours,
            boolean hoursEstimated,
            List<String> reasons,
            List<String> warnings) {}

    /** Điểm không xếp được (chưa có tọa độ): giữ ở cuối ngày. */
    public record UnplacedStop(Long destinationId, String name, String reason) {}

    /**
     * status: OPTIMIZED (có thứ tự tốt hơn), UNCHANGED (thứ tự hiện tại đã hợp lý), SKIPPED (không đủ dữ liệu).
     */
    public record DayPlan(
            Long dayId,
            Integer dayNumber,
            LocalDate date,
            String status,
            String message,
            boolean changed,
            Anchor start,
            Anchor end,
            String departAt,
            String returnAt,
            String sunrise,
            String sunset,
            String matrixSource,
            List<Long> currentOrder,
            List<Long> proposedOrder,
            List<PlannedStop> stops,
            List<UnplacedStop> unplaced,
            Totals before,
            Totals after,
            String summary,
            List<String> warnings) {}

    public record MoveSuggestion(
            Long destinationId,
            String name,
            Long fromDayId,
            Integer fromDayNumber,
            Long toDayId,
            Integer toDayNumber,
            double savedKm,
            int savedMinutes,
            String reason) {}

    /** Body của POST .../optimize/apply. times: giờ đề xuất cho các điểm đang để trống giờ. */
    public record ApplyRequest(String signature, List<ApplyDay> days) {}

    public record ApplyDay(Long dayId, List<Long> destinationIds, Map<Long, String> times) {}

    public record ApplyResponse(int daysUpdated, int timesFilled) {}
}
