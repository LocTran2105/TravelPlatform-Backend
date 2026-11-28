package hcmute.edu.vn.backend.service.itinerary;

import java.util.List;

/**
 * Bài toán xếp lịch một ngày. Điểm i trong {@code stops} là nút i của ma trận;
 * {@code startNode}/{@code endNode} là chỉ số nút của nơi ở (null = không có, đi tự do).
 */
public record DayProblem(
        List<Stop> stops,
        Integer startNode,
        Integer endNode,
        int dayStart,
        int dayEnd,
        TravelMatrix matrix) {

    public int size() {
        return stops.size();
    }
}
