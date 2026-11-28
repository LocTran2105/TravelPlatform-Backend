package hcmute.edu.vn.backend.service.itinerary;

/**
 * Thời gian (phút) và quãng đường (mét) giữa các nút.
 *
 * @param source "GOOGLE" (Google Routes API) hoặc "ESTIMATE" (ước lượng theo đường chim bay)
 */
public record TravelMatrix(double[][] minutes, double[][] meters, String source) {

    public static final String SOURCE_GOOGLE = "GOOGLE";
    public static final String SOURCE_ESTIMATE = "ESTIMATE";

    public double minutes(int from, int to) {
        return minutes[from][to];
    }

    public double meters(int from, int to) {
        return meters[from][to];
    }

    public int size() {
        return minutes.length;
    }
}
