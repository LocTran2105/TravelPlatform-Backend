package hcmute.edu.vn.backend.service.itinerary;

import java.util.List;
import java.util.Locale;

/**
 * Ước lượng thời gian di chuyển khi không có dữ liệu đường đi thật: đường chim bay × hệ số đường vòng
 * (đường đèo, hẻm ở Đà Lạt), chia cho tốc độ trung bình trong phố, cộng thời gian gửi xe/khởi hành.
 */
public final class EstimatedTravelMatrix {

    /** Điểm có tọa độ. */
    public record GeoPoint(double latitude, double longitude) { }

    static final double ROAD_FACTOR = 1.4;

    private EstimatedTravelMatrix() { }

    public static TravelMatrix compute(List<GeoPoint> points, String transport) {
        int n = points.size();
        double[][] minutes = new double[n][n];
        double[][] meters = new double[n][n];
        Mode mode = Mode.of(transport);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                double road = haversineMeters(points.get(i), points.get(j)) * ROAD_FACTOR;
                meters[i][j] = road;
                minutes[i][j] = road / 1000.0 / mode.kmPerHour * 60.0 + mode.overheadMinutes;
            }
        }
        return new TravelMatrix(minutes, meters, TravelMatrix.SOURCE_ESTIMATE);
    }

    public static double haversineMeters(GeoPoint a, GeoPoint b) {
        double r = 6_371_000;
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.latitude())) * Math.cos(Math.toRadians(b.latitude()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    /** Tốc độ trung bình trong phố đồi núi và thời gian gửi/lấy xe mỗi chặng. */
    enum Mode {
        MOTORBIKE(28, 4), CAR(30, 6), WALK(4.5, 0);

        final double kmPerHour;
        final double overheadMinutes;

        Mode(double kmPerHour, double overheadMinutes) {
            this.kmPerHour = kmPerHour;
            this.overheadMinutes = overheadMinutes;
        }

        static Mode of(String transport) {
            String t = transport == null ? "" : transport.trim().toUpperCase(Locale.ROOT);
            return switch (t) {
                case "CAR", "TAXI", "OTO" -> CAR;
                case "WALK", "WALKING" -> WALK;
                default -> MOTORBIKE;
            };
        }
    }
}
