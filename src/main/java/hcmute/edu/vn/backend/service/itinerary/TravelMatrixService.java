package hcmute.edu.vn.backend.service.itinerary;

import hcmute.edu.vn.backend.service.itinerary.EstimatedTravelMatrix.GeoPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ma trận thời gian di chuyển giữa các điểm trong một ngày.
 *
 * <p>Có {@code GOOGLE_MAPS_API_KEY} thì gọi Google Routes API (computeRouteMatrix) để lấy thời gian theo
 * đường thật; không có key, quá giới hạn hoặc lỗi thì ước lượng theo đường chim bay. Kết quả Google chỉ
 * giữ trong bộ nhớ vài giờ (không lưu DB) theo điều khoản sử dụng của Google Maps Platform.</p>
 */
@Slf4j
@Service
public class TravelMatrixService {

    private static final String ROUTES_URL = "https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix";
    private static final String FIELD_MASK = "originIndex,destinationIndex,duration,distanceMeters,status,condition";
    /** Routes API cho tối đa 625 phần tử mỗi yêu cầu (25 × 25). */
    private static final int MAX_GOOGLE_POINTS = 25;
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final int MAX_CACHE_ENTRIES = 500;

    private final JsonMapper jsonMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, CachedMatrix> cache = new ConcurrentHashMap<>();

    @Value("${google.maps.api-key:}")
    private String apiKey;

    private record CachedMatrix(TravelMatrix matrix, Instant expiresAt) { }

    public TravelMatrixService(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public boolean googleEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Ma trận cho các điểm theo đúng thứ tự truyền vào. */
    public TravelMatrix compute(List<GeoPoint> points, String transport) {
        if (points.size() < 2 || !googleEnabled() || points.size() > MAX_GOOGLE_POINTS) {
            return EstimatedTravelMatrix.compute(points, transport);
        }
        String travelMode = googleTravelMode(transport);
        String key = cacheKey(points, travelMode);
        CachedMatrix cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.matrix();

        try {
            TravelMatrix matrix = fetchGoogle(points, travelMode, transport);
            if (matrix == null && "TWO_WHEELER".equals(travelMode)) {
                // Một số khu vực chưa hỗ trợ xe hai bánh: thử lại với ô tô.
                matrix = fetchGoogle(points, "DRIVE", transport);
            }
            if (matrix != null) {
                if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear();
                cache.put(key, new CachedMatrix(matrix, Instant.now().plus(CACHE_TTL)));
                return matrix;
            }
        } catch (Exception ex) {
            log.warn("Google Routes API lỗi, dùng ước lượng: {}", ex.getMessage());
        }
        return EstimatedTravelMatrix.compute(points, transport);
    }

    private TravelMatrix fetchGoogle(List<GeoPoint> points, String travelMode, String transport) throws Exception {
        List<Map<String, Object>> waypoints = new ArrayList<>();
        for (GeoPoint p : points) {
            waypoints.add(Map.of("waypoint", Map.of("location", Map.of("latLng",
                    Map.of("latitude", p.latitude(), "longitude", p.longitude())))));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("origins", waypoints);
        body.put("destinations", waypoints);
        body.put("travelMode", travelMode);

        HttpRequest request = HttpRequest.newBuilder(URI.create(ROUTES_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", FIELD_MASK)
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Google Routes API trả HTTP {} ({}): {}", response.statusCode(), travelMode,
                    response.body() == null ? "" : response.body().replaceAll("\\s+", " ").substring(0, Math.min(300, response.body().length())));
            return null;
        }

        // Cặp nào Google không trả (không có đường) thì dùng giá trị ước lượng.
        TravelMatrix estimate = EstimatedTravelMatrix.compute(points, transport);
        int n = points.size();
        double[][] minutes = new double[n][n];
        double[][] meters = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                minutes[i][j] = estimate.minutes(i, j);
                meters[i][j] = estimate.meters(i, j);
            }
        }
        int filled = 0;
        JsonNode root = jsonMapper.readTree(response.body());
        for (JsonNode element : root) {
            int i = element.path("originIndex").asInt(-1);
            int j = element.path("destinationIndex").asInt(-1);
            if (i < 0 || j < 0 || i >= n || j >= n || i == j) continue;
            if (!"ROUTE_EXISTS".equals(element.path("condition").asString(""))) continue;
            String duration = element.path("duration").asString("");
            if (!duration.endsWith("s")) continue;
            double seconds = Double.parseDouble(duration.substring(0, duration.length() - 1));
            // Cộng thời gian gửi/lấy xe như bản ước lượng (Google chỉ tính thời gian chạy xe).
            minutes[i][j] = seconds / 60.0 + EstimatedTravelMatrix.Mode.of(transport).overheadMinutes;
            meters[i][j] = element.path("distanceMeters").asDouble(meters[i][j]);
            filled++;
        }
        if (filled == 0) return null;
        return new TravelMatrix(minutes, meters, TravelMatrix.SOURCE_GOOGLE);
    }

    private static String googleTravelMode(String transport) {
        return switch (EstimatedTravelMatrix.Mode.of(transport)) {
            case CAR -> "DRIVE";
            case WALK -> "WALK";
            case MOTORBIKE -> "TWO_WHEELER";
        };
    }

    private static String cacheKey(List<GeoPoint> points, String travelMode) {
        StringBuilder sb = new StringBuilder(travelMode);
        for (GeoPoint p : points) {
            sb.append('|').append(String.format(Locale.ROOT, "%.5f,%.5f", p.latitude(), p.longitude()));
        }
        return sb.toString();
    }
}
