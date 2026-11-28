package hcmute.edu.vn.backend.service.itinerary;

import hcmute.edu.vn.backend.service.itinerary.EstimatedTravelMatrix.GeoPoint;
import hcmute.edu.vn.backend.service.itinerary.ItineraryOptimizer.Schedule;
import hcmute.edu.vn.backend.service.itinerary.VisitProfileResolver.PlaceFacts;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItineraryOptimizerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 28); // Thứ 7
    private static final double DALAT_LAT = 11.9404;
    private static final double DALAT_LNG = 108.4583;

    private final ItineraryOptimizer optimizer = new ItineraryOptimizer();

    private static VisitProfile flexible(int dwell) {
        return new VisitProfile("ATTRACTION", "Tham quan", dwell, List.of(), 0, false, "", null);
    }

    private static Stop stop(long id, VisitProfile profile) {
        return new Stop(id, "P" + id, profile.dwellMinutes(), null, false, null, profile);
    }

    /** Ma trận đối xứng từ bảng phút (km = phút để dễ đọc). */
    private static TravelMatrix matrix(double[][] minutes) {
        double[][] meters = new double[minutes.length][minutes.length];
        for (int i = 0; i < minutes.length; i++)
            for (int j = 0; j < minutes.length; j++) meters[i][j] = minutes[i][j] * 1000;
        return new TravelMatrix(minutes, meters, TravelMatrix.SOURCE_ESTIMATE);
    }

    private static int[] ids(DayProblem p, Schedule s) {
        return Arrays.stream(s.order()).map(i -> p.stops().get(i).id().intValue()).toArray();
    }

    @Test
    void userExample_goesOneThenThreeThenTwo() {
        // Điểm 1 cách 2 là 5 km, cách 3 là 1 km; 2 cách 3 là 4 km. Lịch hiện tại: 1 → 2 → 3.
        // Nút 3 là nơi ở, gần điểm 1.
        double[][] m = {
                {0, 5, 1, 0.5},
                {5, 0, 4, 5},
                {1, 4, 0, 1},
                {0.5, 5, 1, 0}};
        List<Stop> stops = List.of(stop(1, flexible(30)), stop(2, flexible(30)), stop(3, flexible(30)));
        DayProblem p = new DayProblem(stops, 3, null, 8 * 60, 21 * 60, matrix(m));

        Schedule current = optimizer.simulate(p, new int[]{0, 1, 2});
        Schedule best = optimizer.optimize(p);

        assertArrayEquals(new int[]{1, 3, 2}, ids(p, best));
        assertTrue(best.travelMeters() < current.travelMeters());
    }

    @Test
    void cloudHuntingGoesFirstAroundSunrise() {
        PlaceFacts cloud = new PlaceFacts("Đồi chè Cầu Đất", "ATTRACTION", List.of(), "săn mây", List.of());
        PlaceFacts museum = new PlaceFacts("Bảo tàng Lâm Đồng", "ATTRACTION", List.of("tourism=museum"), null, List.of());
        PlaceFacts cafe = new PlaceFacts("Tiệm cà phê", "CAFE", List.of("amenity=cafe"), null, List.of());
        List<Stop> stops = new ArrayList<>();
        for (PlaceFacts f : List.of(museum, cafe, cloud)) {
            VisitProfile profile = VisitProfileResolver.resolve(f, DAY, DALAT_LAT, DALAT_LNG, "BALANCED");
            stops.add(new Stop((long) stops.size() + 1, f.name(), profile.dwellMinutes(), null, false, null, profile));
        }
        double[][] m = {
                {0, 10, 40, 8},
                {10, 0, 40, 8},
                {40, 40, 0, 40},
                {8, 8, 40, 0}};
        DayProblem p = new DayProblem(stops, 3, 3, 8 * 60, 21 * 60, matrix(m));

        Schedule best = optimizer.optimize(p);
        assertEquals(3, ids(p, best)[0], "điểm săn mây phải đi đầu tiên");

        int sunrise = SunCalculator.compute(DAY, DALAT_LAT, DALAT_LNG).sunrise();
        int start = best.visits().get(0).start();
        assertTrue(Math.abs(start - sunrise) <= 60, "có mặt quanh bình minh, start=" + start + " sunrise=" + sunrise);
        assertTrue(best.departAt() < 6 * 60, "phải xuất phát trước 6h");
    }

    @Test
    void restaurantLandsInLunchWindow() {
        VisitProfile meal = VisitProfileResolver.resolve(
                new PlaceFacts("Nhà hàng Hoa Sen", "RESTAURANT", List.of(), null, List.of()), DAY, DALAT_LAT, DALAT_LNG, "BALANCED");
        List<Stop> stops = List.of(
                stop(1, meal), stop(2, flexible(120)), stop(3, flexible(120)), stop(4, flexible(90)));
        double[][] m = new double[5][5];
        for (int i = 0; i < 5; i++) for (int j = 0; j < 5; j++) m[i][j] = i == j ? 0 : 10;
        DayProblem p = new DayProblem(stops, 4, 4, 8 * 60, 21 * 60, matrix(m));

        Schedule best = optimizer.optimize(p);
        ItineraryOptimizer.Visit lunch = best.visits().stream().filter(v -> v.stop() == 0).findFirst().orElseThrow();
        boolean inMeal = (lunch.start() >= 11 * 60 && lunch.start() <= 13 * 60)
                || (lunch.start() >= 17 * 60 + 30 && lunch.start() <= 20 * 60);
        assertTrue(inMeal, "nhà hàng phải rơi vào giờ ăn, start=" + TimeWindow.format(lunch.start()));
    }

    @Test
    void avoidsPartnerClosingHours() {
        // Điểm 2 chỉ mở 08:00–10:00 nhưng đang xếp cuối ngày.
        Stop early = new Stop(2L, "Mở sáng", 60, List.of(TimeWindow.of("08:00", "10:00")), true, null, flexible(60));
        List<Stop> stops = List.of(stop(1, flexible(120)), early);
        double[][] m = {{0, 2, 2}, {2, 0, 2}, {2, 2, 0}};
        DayProblem p = new DayProblem(stops, 2, 2, 8 * 60, 21 * 60, matrix(m));

        Schedule current = optimizer.simulate(p, new int[]{0, 1});
        Schedule best = optimizer.optimize(p);
        assertEquals(1, current.hardIssues());
        assertEquals(0, best.hardIssues());
        assertArrayEquals(new int[]{2, 1}, ids(p, best));
    }

    @Test
    void closedAllDayIsReported() {
        Stop closed = new Stop(1L, "Nghỉ", 60, List.of(), true, null, flexible(60));
        DayProblem p = new DayProblem(List.of(closed, stop(2, flexible(60))), null, null, 8 * 60, 21 * 60,
                matrix(new double[][]{{0, 5}, {5, 0}}));
        Schedule s = optimizer.optimize(p);
        assertEquals(1, s.hardIssues());
        assertTrue(s.visits().stream().anyMatch(v -> v.issues().contains(ItineraryOptimizer.ISSUE_CLOSED)));
    }

    @Test
    void fixedAppointmentKeepsItsTime() {
        Stop fixed = new Stop(1L, "Hẹn 15:00", 60, null, false, 15 * 60, flexible(60));
        List<Stop> stops = List.of(fixed, stop(2, flexible(60)), stop(3, flexible(60)));
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) m[i][j] = i == j ? 0 : 15;
        DayProblem p = new DayProblem(stops, 3, 3, 8 * 60, 21 * 60, matrix(m));

        Schedule best = optimizer.optimize(p);
        ItineraryOptimizer.Visit v = best.visits().stream().filter(x -> x.stop() == 0).findFirst().orElseThrow();
        assertEquals(15 * 60, v.start());
        assertEquals(0, best.hardIssues());
    }

    @Test
    void startsAndEndsAtStay() {
        double[][] m = {{0, 3, 4}, {3, 0, 2}, {4, 2, 0}};
        DayProblem p = new DayProblem(List.of(stop(1, flexible(60)), stop(2, flexible(60))), 2, 2, 8 * 60, 21 * 60, matrix(m));
        Schedule s = optimizer.optimize(p);
        assertNotNull(s.returnAt());
        assertTrue(s.returnMinutes() > 0);
        assertEquals(8 * 60 + Math.round((float) m[2][s.order()[0]]), s.visits().get(0).arrive());
    }

    @Test
    void heuristicHandlesManyStops() {
        // 12 điểm trên một đường thẳng, xếp ngược: tối ưu phải đi theo thứ tự trên đường.
        int n = 12;
        List<GeoPoint> points = new ArrayList<>();
        List<Stop> stops = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            points.add(new GeoPoint(11.90 + (n - i) * 0.005, 108.44));
            stops.add(stop(i + 1, flexible(20)));
        }
        points.add(new GeoPoint(11.90, 108.44)); // nơi ở ở đầu đường
        TravelMatrix matrix = EstimatedTravelMatrix.compute(points, "MOTORBIKE");
        DayProblem p = new DayProblem(stops, n, null, 8 * 60, 22 * 60, matrix);

        Schedule current = optimizer.simulate(p, java.util.stream.IntStream.range(0, n).toArray());
        Schedule best = optimizer.optimize(p);
        assertTrue(best.travelMeters() < current.travelMeters() * 0.7,
                "heuristic phải giảm mạnh quãng đường: " + best.travelMeters() + " vs " + current.travelMeters());
    }

    @Test
    void sunTimesForDalatAreReasonable() {
        SunCalculator.SunTimes sun = SunCalculator.compute(DAY, DALAT_LAT, DALAT_LNG);
        // Cuối tháng 11 ở Đà Lạt: mặt trời mọc khoảng 05:45–06:00, lặn khoảng 17:15–17:30.
        assertTrue(sun.sunrise() >= 5 * 60 + 35 && sun.sunrise() <= 6 * 60 + 5, "sunrise=" + TimeWindow.format(sun.sunrise()));
        assertTrue(sun.sunset() >= 17 * 60 + 5 && sun.sunset() <= 17 * 60 + 40, "sunset=" + TimeWindow.format(sun.sunset()));
    }

    @Test
    void profileDetection() {
        assertEquals("SUNSET", VisitProfileResolver.resolve(
                new PlaceFacts("Đồi Robin", "ATTRACTION", List.of(), "ngắm hoàng hôn", List.of()), DAY, DALAT_LAT, DALAT_LNG, null).purpose());
        assertEquals("PHOTO_CAFE", VisitProfileResolver.resolve(
                new PlaceFacts("Cafe view đồi thông", "CAFE", List.of(), "check-in chụp ảnh", List.of()), DAY, DALAT_LAT, DALAT_LNG, null).purpose());
        assertEquals("BREAKFAST", VisitProfileResolver.resolve(
                new PlaceFacts("Bánh căn Nhà Chung", "RESTAURANT", List.of(), null, List.of()), DAY, DALAT_LAT, DALAT_LNG, null).purpose());
        assertEquals("NIGHT", VisitProfileResolver.resolve(
                new PlaceFacts("Chợ đêm Đà Lạt", "ATTRACTION", List.of(), null, List.of()), DAY, DALAT_LAT, DALAT_LNG, null).purpose());
        assertEquals("OUTDOOR", VisitProfileResolver.resolve(
                new PlaceFacts("KDL Thác Datanla", "ATTRACTION", List.of(), null, List.of()), DAY, DALAT_LAT, DALAT_LNG, null).purpose());
    }
}
