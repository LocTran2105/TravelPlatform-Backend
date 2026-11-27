package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Discovers real OpenStreetMap POIs using Photon first for named candidates
 * and Overpass as a secondary source for broader/named POI searches.
 *
 * Photon is useful for resolving AI-proposed names to OSM IDs and coordinates.
 * Overpass remains a fallback because Photon may not index every OSM feature.
 * Neither service verifies live business status, current prices, opening hours,
 * room availability, ratings, or review counts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({"unchecked", "rawtypes"})
public class OsmPlaceDiscoveryService {

    private static final String DEFAULT_PHOTON_URL = "https://photon.komoot.io/api/";
    private static final String DEFAULT_OVERPASS_URL = "https://overpass-api.de/api/interpreter";
    private static final int MAX_RADIUS_METERS = 15_000;
    /** Đủ ngần này POI còn mới trong DB thì không cần gọi Overpass. */
    private static final int MIN_FRESH_DB_RESULTS = 8;
    /** Dấu hiệu "nổi tiếng" lưu trong categories (POI có wikidata/wikipedia trên OSM). */
    public static final String NOTABLE_CATEGORY = "notable=wiki";
    private static final int MAX_RESULTS = 20;
    private static final int MAX_ELEMENTS_TO_PROCESS = 500;
    private static final int MAX_NAMED_CANDIDATES = 8;

    private final PlaceRepository placeRepository;

    @Value("${osm.photon.url:}")
    private String photonUrl;

    @Value("${osm.overpass.url:}")
    private String overpassUrl;

    /** Optional comma-separated fallback interpreter endpoints. */
    @Value("${osm.overpass.fallback-urls:}")
    private String overpassFallbackUrls;

    @Value("${osm.user-agent:TravelPlatform-HCMUTE/1.0 (contact: configure-your-contact-email)}")
    private String userAgent;

    @Value("${osm.cache.ttl-seconds:21600}")
    private long cacheTtlSeconds;

    @Value("${osm.photon.cache-ttl-seconds:86400}")
    private long photonCacheTtlSeconds;

    /**
     * Khi mọi endpoint Overpass đều lỗi (timeout/502/remark), tạm bỏ qua Overpass trong khoảng này
     * để các lượt AI tiếp theo dùng DB fallback ngay thay vì chờ ~40s/lượt. Chỉ áp dụng cho discovery
     * của Traveler; luồng tìm POI cho Admin vẫn luôn gọi Overpass để tránh tạo PARTNER nhầm.
     */
    @Value("${osm.overpass.cooldown-seconds:90}")
    private long overpassCooldownSeconds;

    private volatile Instant overpassCooldownUntil = Instant.EPOCH;

    /**
     * POI OSM đã lưu trong DB còn "tươi" trong khoảng này thì dùng luôn, không gọi Overpass
     * (dữ liệu OSM thay đổi chậm; sau khi khởi động lại backend vẫn trả kết quả ngay).
     */
    @Value("${osm.db.fresh-hours:168}")
    private long dbFreshHours;

    /** Thời gian chờ mỗi máy chủ Overpass cho luồng gợi ý của Traveler (Admin dùng thời gian dài hơn). */
    @Value("${osm.overpass.read-timeout-ms:8000}")
    private int overpassReadTimeoutMs;

    /** Máy chủ đầu chưa trả lời sau khoảng này thì gửi song song sang máy chủ kế tiếp ("hedged request"). */
    @Value("${osm.overpass.hedge-delay-ms:2500}")
    private long overpassHedgeDelayMs;

    /** Tổng thời gian tối đa chờ Overpass cho một lượt gợi ý. */
    @Value("${osm.overpass.total-timeout-ms:10000}")
    private long overpassTotalTimeoutMs;

    private static final int ADMIN_READ_TIMEOUT_MS = 15_000;
    private static final long ADMIN_TOTAL_TIMEOUT_MS = 20_000;

    /** Luồng riêng cho các lời gọi Overpass song song (daemon để không giữ JVM khi tắt). */
    private static final java.util.concurrent.ExecutorService OVERPASS_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "overpass-client");
                thread.setDaemon(true);
                return thread;
            });

    private final ConcurrentMap<String, CachedPlaces> discoveryCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CachedCoordinates> geocodeCache = new ConcurrentHashMap<>();

    private RestTemplate createHttpClient(int readTimeoutMs) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    private String resolvePhotonUrl() {
        return hasText(photonUrl) && !photonUrl.contains("[")
                ? photonUrl.trim()
                : DEFAULT_PHOTON_URL;
    }

    private List<String> resolveOverpassUrls() {
        Set<String> urls = new LinkedHashSet<>();
        if (hasText(overpassUrl) && !overpassUrl.contains("[")) {
            urls.add(overpassUrl.trim());
        } else {
            urls.add(DEFAULT_OVERPASS_URL);
        }

        if (hasText(overpassFallbackUrls)) {
            for (String candidate : overpassFallbackUrls.split(",")) {
                if (hasText(candidate) && !candidate.contains("[")) {
                    urls.add(candidate.trim());
                }
            }
        }
        return new ArrayList<>(urls);
    }

    /** Resolves destination center as [latitude, longitude]. */
    public Optional<double[]> resolveDestinationCoordinates(String destination) {
        if (!hasText(destination)) {
            return Optional.empty();
        }

        String cacheKey = destination.trim().toLowerCase(Locale.ROOT);
        CachedCoordinates cached = geocodeCache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return Optional.of(cached.coordinates().clone());
        }
        if (cached != null) {
            geocodeCache.remove(cacheKey, cached);
        }

        try {
            URI uri = UriComponentsBuilder.fromUriString(resolvePhotonUrl())
                    .queryParam("q", destination.trim())
                    // Vietnam center is only a search bias, not a claimed destination coordinate.
                    .queryParam("lat", 14.0583)
                    .queryParam("lon", 108.2772)
                    .queryParam("zoom", 5)
                    .queryParam("limit", 1)
                    .build()
                    .encode()
                    .toUri();

            ResponseEntity<Map> response = createHttpClient(8_000).exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(buildHeaders()),
                    Map.class
            );
            Map body = response.getBody();

            if (body != null && body.get("features") instanceof List<?> features && !features.isEmpty()) {
                Map first = (Map) features.get(0);
                Map geometry = first.get("geometry") instanceof Map ? (Map) first.get("geometry") : null;
                List coordinates = geometry != null && geometry.get("coordinates") instanceof List
                        ? (List) geometry.get("coordinates") : null;

                if (coordinates != null && coordinates.size() >= 2
                        && coordinates.get(0) instanceof Number lonValue
                        && coordinates.get(1) instanceof Number latValue) {
                    double latitude = latValue.doubleValue();
                    double longitude = lonValue.doubleValue();
                    if (isValidCoordinates(latitude, longitude)) {
                        double[] result = new double[]{latitude, longitude};
                        long ttl = Math.max(300L, photonCacheTtlSeconds);
                        geocodeCache.put(cacheKey,
                                new CachedCoordinates(result, Instant.now().plusSeconds(ttl)));
                        return Optional.of(result.clone());
                    }
                }
            }

            log.warn("Photon không tìm thấy tọa độ cho destination='{}'", destination);
        } catch (HttpStatusCodeException ex) {
            log.warn("Photon trả HTTP {} khi geocode '{}': {}",
                    ex.getStatusCode().value(), destination, safeMessage(ex));
        } catch (Exception ex) {
            log.warn("Không thể geocode destination='{}' qua Photon: {}", destination, safeMessage(ex));
        }

        return Optional.empty();
    }

    /**
     * Search broad POIs for travellers.
     * Result = PARTNER công khai (đọc mới mỗi lần, không cache) + OSM (Overpass/cache) hoặc DB fallback.
     * PARTNER đứng trước để không bị cắt bởi limit; RecommendationService xếp hạng lại sau đó.
     */
    public List<Place> discoverAndPersistPlaces(
            String destination, String category, int radiusMeters, int limit) {

        if (!hasText(destination)) {
            return Collections.emptyList();
        }

        long startedAt = System.currentTimeMillis();
        String normalizedDestination = destination.trim();
        String normalizedCategory = normalizeCategory(category);
        int safeRadius = Math.min(Math.max(radiusMeters, 1_000), MAX_RADIUS_METERS);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_RESULTS);

        double[] center = resolveDestinationCoordinates(normalizedDestination).orElse(null);
        List<Place> osmOrFallback = discoverOsmOrFallback(
                normalizedDestination, normalizedCategory, safeRadius, safeLimit, center);
        List<Place> partners = findEligiblePartnerPlaces(
                normalizedDestination, normalizedCategory, center, safeRadius);

        LinkedHashMap<Long, Place> merged = new LinkedHashMap<>();
        for (Place place : partners) {
            if (place != null && place.getId() != null) merged.putIfAbsent(place.getId(), place);
        }
        for (Place place : osmOrFallback) {
            if (place != null && place.getId() != null) merged.putIfAbsent(place.getId(), place);
        }
        List<Place> result = merged.values().stream().limit(safeLimit).collect(Collectors.toList());

        log.info("Discovery xong: destination='{}', category={}, partner={}, osmOrDb={}, returned={}, providers={}, elapsedMs={}",
                normalizedDestination, normalizedCategory, partners.size(), osmOrFallback.size(),
                result.size(), countByProvider(result), System.currentTimeMillis() - startedAt);
        return result;
    }

    /**
     * Địa điểm PARTNER đủ điều kiện công khai cho điểm đến.
     * Khớp nếu tên điểm đến tương đương ("Đà Lạt" ~ "TP. Đà Lạt" ~ "Da Lat, Lâm Đồng")
     * hoặc nằm trong bán kính tìm kiếm quanh tâm điểm đến (khi Photon có tọa độ).
     * Bảng PARTNER nhỏ vì mỗi dòng đều do Admin duyệt, nên lọc khớp tên ở Java là an toàn.
     */
    public List<Place> findEligiblePartnerPlaces(
            String destination, String category, double[] center, int radiusMeters) {
        List<Place> candidates;
        try {
            candidates = placeRepository.findEligiblePartnerPlacesByPlaceType(normalizeCategory(category));
        } catch (Exception ex) {
            log.warn("Không đọc được PARTNER places cho '{}'/{}: {}", destination, category, safeMessage(ex));
            return Collections.emptyList();
        }
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        String wantedKey = destinationKey(destination);
        List<Place> matched = new ArrayList<>();
        for (Place place : candidates) {
            if (place == null) continue;
            boolean sameDestination = !wantedKey.isEmpty()
                    && destinationKeysMatch(wantedKey, destinationKey(place.getDestination()));
            boolean withinRadius = center != null
                    && place.getLatitude() != null && place.getLongitude() != null
                    && calculateDistanceMeters(center[0], center[1],
                    place.getLatitude().doubleValue(), place.getLongitude().doubleValue()) <= radiusMeters;
            if (sameDestination || withinRadius) {
                matched.add(place);
            }
        }
        return matched;
    }

    /** "Thành phố Đà Lạt, Lâm Đồng" -> "da lat". Bỏ dấu, đ->d, bỏ tiền tố hành chính, chỉ lấy phần trước dấu phẩy. */
    static String destinationKey(String raw) {
        if (raw == null) return "";
        String value = raw.split(",", 2)[0];
        value = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        value = value.replaceFirst("^(thanh pho|tp|tinh|thi xa|tx|huyen|quan|city of)\\s+", "");
        return value.trim();
    }

    private static boolean destinationKeysMatch(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        // "da lat" khớp "da lat lam dong" nhưng "an" không khớp "an giang" (yêu cầu tối thiểu 4 ký tự).
        boolean aIsShorter = a.length() <= b.length();
        String shorter = aIsShorter ? a : b;
        String longer = aIsShorter ? b : a;
        return shorter.length() >= 4 && (" " + longer + " ").contains(" " + shorter + " ");
    }

    private static Map<String, Long> countByProvider(List<Place> places) {
        return places.stream().collect(Collectors.groupingBy(
                place -> place.getProvider() == null ? "UNKNOWN" : place.getProvider().toUpperCase(Locale.ROOT),
                LinkedHashMap::new, Collectors.counting()));
    }

    /** OSM qua Overpass (có cache), hoặc DB fallback khi không có tâm/Overpass lỗi/rỗng. */
    private List<Place> discoverOsmOrFallback(
            String normalizedDestination, String normalizedCategory,
            int safeRadius, int safeLimit, double[] center) {

        String cacheKey = normalizedDestination.toLowerCase(Locale.ROOT)
                + "|" + normalizedCategory + "|" + safeRadius;

        CachedPlaces cached = discoveryCache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.places().stream().limit(safeLimit).collect(Collectors.toList());
        }
        if (cached != null) {
            discoveryCache.remove(cacheKey, cached);
        }

        if (center == null) {
            log.warn("Không có tâm tìm kiếm cho '{}'; dùng dữ liệu DB đúng category làm fallback.",
                    normalizedDestination);
            return getFallbackFromDb(normalizedDestination, normalizedCategory, safeLimit);
        }

        // DB trước: đã có đủ POI OSM lấy về gần đây thì trả ngay (vài ms) thay vì chờ Overpass (có thể 10–30s).
        List<Place> fresh = placeRepository.findFreshOsmPlaces(
                normalizedDestination, normalizedCategory,
                LocalDateTime.now().minusHours(Math.max(1L, dbFreshHours)), safeLimit);
        if (fresh != null && fresh.size() >= Math.min(safeLimit, MIN_FRESH_DB_RESULTS)) {
            log.info("Dùng {} POI OSM còn mới trong DB cho '{}'/{}; bỏ qua Overpass.",
                    fresh.size(), normalizedDestination, normalizedCategory);
            long ttl = Math.max(300L, cacheTtlSeconds);
            discoveryCache.put(cacheKey, new CachedPlaces(List.copyOf(fresh), Instant.now().plusSeconds(ttl)));
            return fresh;
        }

        if (Instant.now().isBefore(overpassCooldownUntil)) {
            log.info("Overpass đang trong thời gian nghỉ tới {} sau lỗi liên tiếp; dùng DB fallback cho '{}'/{}.",
                    overpassCooldownUntil, normalizedDestination, normalizedCategory);
            return getFallbackFromDb(normalizedDestination, normalizedCategory, safeLimit);
        }

        String query = buildOverpassQuery(normalizedCategory,
                center[0], center[1], safeRadius);

        OverpassFetch fetch = fetchOverpass(query, normalizedDestination, normalizedCategory,
                overpassReadTimeoutMs, overpassTotalTimeoutMs);
        if (fetch.allEndpointsFailed()) {
            overpassCooldownUntil = Instant.now().plusSeconds(Math.max(15L, overpassCooldownSeconds));
            log.warn("Mọi endpoint Overpass đều lỗi; tạm dùng DB fallback tới {}.", overpassCooldownUntil);
        }
        List<Map> elements = fetch.elements();
        if (elements.isEmpty()) {
            log.warn("Overpass không trả POI cho destination='{}', category='{}'. Dùng DB fallback đúng loại.",
                    normalizedDestination, normalizedCategory);
            return getFallbackFromDb(normalizedDestination, normalizedCategory, safeLimit);
        }

        List<Place> persisted = new ArrayList<>();
        Set<String> seenProviderIds = new LinkedHashSet<>();
        int processed = 0;

        for (Map element : elements) {
            if (processed++ >= MAX_ELEMENTS_TO_PROCESS || persisted.size() >= safeLimit) {
                break;
            }
            if (element == null || !(element.get("tags") instanceof Map tags)) {
                continue;
            }
            if (!tags.containsKey("name") || !hasText(String.valueOf(tags.get("name")))) {
                continue;
            }

            String name = String.valueOf(tags.get("name")).trim();
            String osmType = String.valueOf(element.getOrDefault("type", "node"));
            String osmId = String.valueOf(element.get("id"));
            if (!hasText(osmId) || "null".equalsIgnoreCase(osmId)) {
                continue;
            }

            String providerPlaceId = osmType.toLowerCase(Locale.ROOT) + "/" + osmId;
            if (!seenProviderIds.add(providerPlaceId)) {
                continue;
            }

            Double latitude = extractLatitude(element);
            Double longitude = extractLongitude(element);
            if (!isValidCoordinates(latitude, longitude)) {
                continue;
            }

            // Avoid mixing unrelated POI types into the requested category.
            if (!matchesOsmCategory(tags, normalizedCategory)) {
                continue;
            }

            String address = buildOsmAddress(tags, normalizedDestination);
            List<String> categories = extractOsmCategories(tags);
            try {
                Place place = upsertOsmPlace(providerPlaceId, name, address,
                        normalizedDestination, normalizedCategory,
                        latitude, longitude, categories);
                persisted.add(place);
            } catch (Exception ex) {
                log.warn("Không lưu được POI OSM {}/{} '{}': {}",
                        osmType, osmId, name, safeMessage(ex));
            }
        }

        if (!persisted.isEmpty()) {
            long ttl = Math.max(300L, cacheTtlSeconds);
            discoveryCache.put(cacheKey,
                    new CachedPlaces(List.copyOf(persisted), Instant.now().plusSeconds(ttl)));
            return persisted;
        }

        log.warn("Overpass có {} elements nhưng không lưu được POI hợp lệ cho '{}'. Dùng DB fallback.",
                elements.size(), normalizedDestination);
        return getFallbackFromDb(normalizedDestination, normalizedCategory, safeLimit);
    }

    /**
     * Searches OSM around the Partner-submitted coordinates for the Admin review map.
     * Results are persisted as provider=OSM candidates, but are NEVER linked to a Business here.
     */
    public List<Place> discoverNearbyPlacesForAdmin(
            String destination,
            BigDecimal latitude,
            BigDecimal longitude,
            String businessType,
            int radiusMeters,
            int limit) {

        if (latitude == null || longitude == null
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("Business chưa có tọa độ hợp lệ để tìm OSM.");
        }

        String safeDestination = hasText(destination) ? destination.trim() : "Việt Nam";
        if (safeDestination.length() > 100) safeDestination = safeDestination.substring(0, 100);
        String category = normalizeCategory(businessType);
        int safeRadius = Math.max(100, Math.min(radiusMeters, MAX_RADIUS_METERS));
        int safeLimit = Math.max(1, Math.min(limit, 50));

        String query = buildOverpassQuery(
                category, latitude.doubleValue(), longitude.doubleValue(), safeRadius);
        OverpassFetch fetch = fetchOverpass(query, safeDestination, "ADMIN_" + category,
                ADMIN_READ_TIMEOUT_MS, ADMIN_TOTAL_TIMEOUT_MS);
        if (fetch.allEndpointsFailed()) {
            // Overpass lỗi nhưng DB có thể đã lưu POI OSM quanh đây (từ lần tìm trước): cho Admin chọn
            // các POI đó để liên kết, tránh phải tạo Place PARTNER trùng với địa điểm OSM.
            List<Place> saved = findSavedOsmPlacesNear(
                    category, latitude.doubleValue(), longitude.doubleValue(), safeRadius, safeLimit);
            if (!saved.isEmpty()) {
                log.warn("Overpass lỗi khi Admin kiểm tra '{}'/{}; dùng {} POI OSM đã lưu trong DB.",
                        safeDestination, category, saved.size());
                return saved;
            }
            // Khác với "không có POI": mọi máy chủ Overpass đều lỗi/quá tải nên CHƯA kiểm tra được.
            // Báo rõ cho Admin thay vì trả danh sách rỗng (dễ hiểu nhầm là OSM không có địa điểm).
            throw new IllegalStateException(
                    "Máy chủ OpenStreetMap (Overpass) đang quá tải hoặc không phản hồi nên chưa kiểm tra được POI OSM"
                            + " quanh vị trí này. Bấm \"Tìm lại POI OSM\" sau ít phút, hoặc dùng \"Tạo Place PARTNER\""
                            + " nếu Admin đã xác minh được vị trí.");
        }
        List<Map> elements = fetch.elements();
        if (elements.isEmpty()) return Collections.emptyList();

        List<Place> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int processed = 0;
        for (Map element : elements) {
            if (processed++ >= MAX_ELEMENTS_TO_PROCESS || candidates.size() >= safeLimit) break;
            if (element == null || !(element.get("tags") instanceof Map tags)) continue;
            Object rawName = tags.get("name");
            if (rawName == null || !hasText(String.valueOf(rawName))) continue;
            if (!matchesOsmCategory(tags, category)) continue;

            String osmType = String.valueOf(element.getOrDefault("type", "node")).toLowerCase(Locale.ROOT);
            String osmId = String.valueOf(element.get("id"));
            if (!hasText(osmId) || "null".equalsIgnoreCase(osmId)) continue;
            String providerPlaceId = osmType + "/" + osmId;
            if (!seen.add(providerPlaceId)) continue;

            Double lat = extractLatitude(element);
            Double lon = extractLongitude(element);
            if (!isValidCoordinates(lat, lon)) continue;

            try {
                Place place = upsertOsmPlace(
                        providerPlaceId,
                        String.valueOf(rawName).trim(),
                        buildOsmAddress(tags, safeDestination),
                        safeDestination,
                        category,
                        lat,
                        lon,
                        extractOsmCategories(tags));
                // AdminPartnerBusinessService filters out records assigned to another Business.
                candidates.add(place);
            } catch (Exception ex) {
                log.warn("Không lưu được OSM candidate cho Admin '{}': {}",
                        rawName, safeMessage(ex));
            }
        }

        return candidates;
    }

    /**
     * Verify AI-proposed names. Photon is tried first because it resolves a name directly
     * to an OSM feature. Overpass is used only when Photon cannot verify any candidate.
     */
    public List<Place> discoverNamedPlaces(
            String destination,
            String category,
            List<String> candidateNames,
            int radiusMeters,
            int limit) {

        if (!hasText(destination) || candidateNames == null || candidateNames.isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedDestination = destination.trim();
        String normalizedCategory = normalizeCategory(category);
        int safeRadius = Math.min(Math.max(radiusMeters, 1_000), MAX_RADIUS_METERS);
        int safeLimit = Math.min(Math.max(limit, 1), 10);

        Map<String, String> uniqueNames = new LinkedHashMap<>();
        for (String rawName : candidateNames) {
            if (rawName == null) {
                continue;
            }
            String name = rawName.trim();
            String normalizedName = normalizePlaceName(name);
            if (!name.isBlank() && name.length() <= 100 && !normalizedName.isBlank()) {
                uniqueNames.putIfAbsent(normalizedName, name);
            }
            if (uniqueNames.size() >= MAX_NAMED_CANDIDATES) {
                break;
            }
        }
        List<String> safeNames = new ArrayList<>(uniqueNames.values());

        if (safeNames.isEmpty()) {
            return Collections.emptyList();
        }

        Optional<double[]> centerOpt = resolveDestinationCoordinates(normalizedDestination);
        if (centerOpt.isEmpty()) {
            log.warn("Không có tọa độ trung tâm để xác minh candidate của '{}'.", normalizedDestination);
            return matchNamedPlacesFromDb(normalizedDestination, normalizedCategory, safeNames, safeLimit);
        }

        double[] center = centerOpt.get();

        // Phase 1: query Photon candidate-by-candidate. It returns the OSM ID and geometry
        // needed by the frontend without waiting for a busy Overpass interpreter.
        List<Place> photonMatches = discoverNamedPlacesWithPhoton(
                normalizedDestination,
                normalizedCategory,
                safeNames,
                center[0],
                center[1],
                safeRadius,
                safeLimit
        );

        if (!photonMatches.isEmpty()) {
            log.info("Photon xác minh candidate: destination='{}', category={}, verified={}/{}",
                    normalizedDestination, normalizedCategory, photonMatches.size(), safeNames.size());
            return photonMatches;
        }

        // Phase 2: Photon did not find a match; try Overpass as a secondary source.
        // Overpass đang bị đánh dấu quá tải thì bỏ qua luôn (tránh chờ thêm ~10s mỗi tin nhắn).
        if (Instant.now().isBefore(overpassCooldownUntil)) {
            log.info("Overpass đang nghỉ tới {}; chỉ đối chiếu candidate với DB.", overpassCooldownUntil);
            return matchNamedPlacesFromDb(normalizedDestination, normalizedCategory, safeNames, safeLimit);
        }
        String query = buildNamedPlacesQuery(normalizedCategory, safeNames,
                center[0], center[1], safeRadius);
        OverpassFetch namedFetch = fetchOverpass(query, normalizedDestination, normalizedCategory + "_NAMED",
                overpassReadTimeoutMs, overpassTotalTimeoutMs);
        if (namedFetch.allEndpointsFailed()) {
            overpassCooldownUntil = Instant.now().plusSeconds(Math.max(15L, overpassCooldownSeconds));
        }
        List<Map> elements = namedFetch.elements();

        if (elements.isEmpty()) {
            log.info("Photon/Overpass chưa xác minh được candidate: destination='{}', category={}, candidates={}",
                    normalizedDestination, normalizedCategory, safeNames.size());
            return matchNamedPlacesFromDb(normalizedDestination, normalizedCategory, safeNames, safeLimit);
        }

        Map<String, Place> byNormalizedName = new LinkedHashMap<>();
        for (Map element : elements) {
            if (element == null || !(element.get("tags") instanceof Map tags)) {
                continue;
            }
            if (!matchesOsmCategory(tags, normalizedCategory)) {
                continue;
            }

            Object rawName = tags.get("name");
            if (rawName == null || !hasText(String.valueOf(rawName))) {
                continue;
            }
            String name = String.valueOf(rawName).trim();
            if (!containsCandidateName(safeNames, name)) {
                continue;
            }

            String osmType = String.valueOf(element.getOrDefault("type", "node"));
            String osmId = String.valueOf(element.get("id"));
            if (!hasText(osmId) || "null".equalsIgnoreCase(osmId)) {
                continue;
            }

            Double latitude = extractLatitude(element);
            Double longitude = extractLongitude(element);
            if (!isValidCoordinates(latitude, longitude)) {
                continue;
            }

            String providerPlaceId = osmType.toLowerCase(Locale.ROOT) + "/" + osmId;
            try {
                Place place = upsertOsmPlace(providerPlaceId, name,
                        buildOsmAddress(tags, normalizedDestination), normalizedDestination,
                        normalizedCategory, latitude, longitude, extractOsmCategories(tags));
                byNormalizedName.putIfAbsent(normalizePlaceName(name), place);
            } catch (Exception ex) {
                log.warn("Không lưu được OSM candidate '{}': {}", name, safeMessage(ex));
            }
        }

        List<Place> ordered = new ArrayList<>();
        Set<Long> seenIds = new LinkedHashSet<>();
        for (String candidate : safeNames) {
            Place match = byNormalizedName.get(normalizePlaceName(candidate));
            if (match != null && match.getId() != null && seenIds.add(match.getId())) {
                ordered.add(match);
            }
        }

        if (!ordered.isEmpty()) {
            log.info("Overpass xác minh candidate: destination='{}', category={}, verified={}/{}",
                    normalizedDestination, normalizedCategory, ordered.size(), safeNames.size());
            return ordered.stream().limit(safeLimit).collect(Collectors.toList());
        }

        return matchNamedPlacesFromDb(normalizedDestination, normalizedCategory, safeNames, safeLimit);
    }

    /** Query Photon for each candidate and accept only exact normalized name + category + nearby OSM ID. */
    private List<Place> discoverNamedPlacesWithPhoton(
            String destination,
            String category,
            List<String> candidateNames,
            double centerLatitude,
            double centerLongitude,
            int radiusMeters,
            int limit) {

        Map<String, Place> verified = new LinkedHashMap<>();
        RestTemplate photonClient = createHttpClient(4_500);
        int consecutiveFailures = 0;

        for (String candidate : candidateNames) {
            if (verified.size() >= limit) {
                break;
            }

            try {
                URI uri = UriComponentsBuilder.fromUriString(resolvePhotonUrl())
                        .queryParam("q", candidate)
                        .queryParam("lat", centerLatitude)
                        .queryParam("lon", centerLongitude)
                        .queryParam("zoom", 12)
                        .queryParam("limit", 5)
                        .build()
                        .encode()
                        .toUri();

                ResponseEntity<Map> response = photonClient.exchange(
                        uri,
                        HttpMethod.GET,
                        new HttpEntity<>(buildHeaders()),
                        Map.class
                );
                Map body = response.getBody();
                if (body == null || !(body.get("features") instanceof List<?> features)) {
                    consecutiveFailures = 0;
                    continue;
                }

                Place matchedPlace = null;
                for (Object featureObject : features) {
                    if (!(featureObject instanceof Map feature)
                            || !(feature.get("properties") instanceof Map properties)) {
                        continue;
                    }

                    String osmName = Objects.toString(properties.get("name"), "").trim();
                    if (!namesMatch(candidate, osmName)) {
                        continue;
                    }
                    if (!matchesPhotonCategory(properties, category)) {
                        continue;
                    }

                    Object rawOsmId = properties.get("osm_id");
                    Object rawOsmType = properties.get("osm_type");
                    if (rawOsmId == null || rawOsmType == null) {
                        continue;
                    }

                    String osmPrefix = normalizePhotonOsmType(String.valueOf(rawOsmType));
                    if (osmPrefix == null) {
                        continue;
                    }

                    String osmId = rawOsmId instanceof Number number
                            ? Long.toString(number.longValue())
                            : String.valueOf(rawOsmId).trim();
                    if (!hasText(osmId) || "null".equalsIgnoreCase(osmId)) {
                        continue;
                    }

                    if (!(feature.get("geometry") instanceof Map geometry)
                            || !(geometry.get("coordinates") instanceof List<?> coordinates)
                            || coordinates.size() < 2
                            || !(coordinates.get(0) instanceof Number longitudeValue)
                            || !(coordinates.get(1) instanceof Number latitudeValue)) {
                        continue;
                    }

                    double latitude = latitudeValue.doubleValue();
                    double longitude = longitudeValue.doubleValue();
                    if (!isValidCoordinates(latitude, longitude)) {
                        continue;
                    }

                    double distanceMeters = calculateDistanceMeters(
                            centerLatitude, centerLongitude, latitude, longitude);
                    if (distanceMeters > radiusMeters) {
                        continue;
                    }

                    String providerPlaceId = osmPrefix + "/" + osmId;
                    String osmKey = Objects.toString(properties.get("osm_key"), "");
                    String osmValue = Objects.toString(properties.get("osm_value"), "");
                    List<String> categories = new ArrayList<>();
                    if (hasText(osmKey) && hasText(osmValue)) {
                        categories.add(osmKey + "=" + osmValue);
                    }

                    matchedPlace = upsertOsmPlace(
                            providerPlaceId,
                            osmName,
                            buildPhotonAddress(properties, destination),
                            destination,
                            category,
                            latitude,
                            longitude,
                            categories
                    );
                    break;
                }

                consecutiveFailures = 0;
                if (matchedPlace != null) {
                    verified.putIfAbsent(matchedPlace.getProviderPlaceId(), matchedPlace);
                }

            } catch (HttpStatusCodeException ex) {
                consecutiveFailures++;
                log.warn("Photon trả HTTP {} khi xác minh '{}': {}",
                        ex.getStatusCode().value(), candidate, safeMessage(ex));
            } catch (Exception ex) {
                consecutiveFailures++;
                log.warn("Photon lỗi khi xác minh candidate '{}': {}", candidate, safeMessage(ex));
            }

            // Photon unavailable: do not spend the full request timeout on every remaining name.
            if (consecutiveFailures >= 2) {
                log.warn("Photon liên tiếp lỗi {} lần; chuyển sang Overpass fallback.", consecutiveFailures);
                break;
            }
        }

        return new ArrayList<>(verified.values());
    }

    private String normalizePhotonOsmType(String rawType) {
        if (rawType == null) {
            return null;
        }
        return switch (rawType.trim().toUpperCase(Locale.ROOT)) {
            case "N", "NODE" -> "node";
            case "W", "WAY" -> "way";
            case "R", "RELATION" -> "relation";
            default -> null;
        };
    }

    private boolean matchesPhotonCategory(Map properties, String category) {
        String key = Objects.toString(properties.get("osm_key"), "").toLowerCase(Locale.ROOT);
        String value = Objects.toString(properties.get("osm_value"), "").toLowerCase(Locale.ROOT);

        return switch (category) {
            case "RESTAURANT" -> "amenity".equals(key)
                    && Set.of("restaurant", "food_court", "fast_food", "bbq").contains(value);
            case "CAFE" -> "amenity".equals(key) && "cafe".equals(value);
            case "ACCOMMODATION" -> "tourism".equals(key)
                    && Set.of("hotel", "guest_house", "hostel", "chalet", "resort", "motel", "apartment")
                    .contains(value);
            // Điểm tham quan ở Việt Nam thường được gắn thẻ đa dạng trên OSM: hồ (natural=water), chợ
            // (amenity=marketplace), chùa/nhà thờ (amenity=place_of_worship), ga cổ (railway=station)...
            // Tên đã do người dùng/AI nêu đích danh nên chấp nhận các nhóm này, vẫn loại chỗ ở và hàng quán.
            default -> ("tourism".equals(key)
                    && !Set.of("hotel", "guest_house", "hostel", "motel", "apartment", "chalet",
                    "camp_site", "caravan_site", "information").contains(value))
                    || "attraction".equals(key)
                    || "historic".equals(key)
                    || "leisure".equals(key)
                    || "natural".equals(key)
                    || ("waterway".equals(key) && "waterfall".equals(value))
                    || ("amenity".equals(key) && Set.of("place_of_worship", "marketplace", "theatre",
                    "arts_centre", "fountain").contains(value))
                    || ("building".equals(key) && Set.of("church", "cathedral", "temple", "pagoda",
                    "chapel", "monastery").contains(value))
                    || ("railway".equals(key) && "station".equals(value))
                    || ("place".equals(key) && "square".equals(value));
        };
    }

    private String buildPhotonAddress(Map properties, String fallbackDestination) {
        List<String> parts = new ArrayList<>();
        for (String key : List.of(
                "housenumber", "street", "district", "locality", "city", "state", "country")) {
            Object raw = properties.get(key);
            if (raw != null && hasText(String.valueOf(raw))) {
                String value = String.valueOf(raw).trim();
                if (!parts.contains(value)) {
                    parts.add(value);
                }
            }
        }
        if (parts.isEmpty() && hasText(fallbackDestination)) {
            parts.add(fallbackDestination);
        }
        return String.join(", ", parts);
    }

    /** POI OSM đã lưu, đúng loại, trong bán kính radiusMeters quanh (lat, lon); gần nhất trước. */
    private List<Place> findSavedOsmPlacesNear(
            String category, double lat, double lon, int radiusMeters, int limit) {
        double dLat = radiusMeters / 111_320.0;
        double dLon = radiusMeters / (111_320.0 * Math.max(0.01, Math.cos(Math.toRadians(lat))));
        return placeRepository.findOsmPlacesInBox(category, lat - dLat, lat + dLat, lon - dLon, lon + dLon)
                .stream()
                .filter(p -> p.getLatitude() != null && p.getLongitude() != null)
                .map(p -> Map.entry(p, calculateDistanceMeters(
                        lat, lon, p.getLatitude().doubleValue(), p.getLongitude().doubleValue())))
                .filter(e -> e.getValue() <= radiusMeters)
                .sorted(Map.Entry.comparingByValue())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static double calculateDistanceMeters(
            double latitude1, double longitude1, double latitude2, double longitude2) {
        final double earthRadiusMeters = 6_371_000.0;
        double dLat = Math.toRadians(latitude2 - latitude1);
        double dLon = Math.toRadians(longitude2 - longitude1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(latitude1))
                * Math.cos(Math.toRadians(latitude2))
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        return 2.0 * earthRadiusMeters * Math.asin(Math.sqrt(a));
    }

    private List<Place> matchNamedPlacesFromDb(
            String destination, String category, List<String> names, int limit) {
        List<Place> candidates = getFallbackFromDb(destination, category, 100);
        Set<String> wanted = new LinkedHashSet<>();
        for (String name : names) {
            wanted.add(normalizePlaceName(name));
        }
        return candidates.stream()
                // Only OSM rows count as OSM verification; manual/Google seed records do not.
                .filter(place -> place != null && "OSM".equalsIgnoreCase(place.getProvider()))
                .filter(place -> wanted.contains(normalizePlaceName(place.getName())))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private String buildNamedPlacesQuery(
            String category, List<String> names, double latitude, double longitude, int radiusMeters) {
        String around = String.format(Locale.US, "(around:%d,%.6f,%.6f)",
                radiusMeters, latitude, longitude);
        String regex = names.stream()
                .map(this::escapeRegexLiteral)
                .collect(Collectors.joining("|"));
        String qlRegex = escapeOverpassString("^(" + regex + ")$");
        String nameFilter = "[\"name\"~\"" + qlRegex + "\",i]";
        List<String> selectors = new ArrayList<>();

        switch (category) {
            case "RESTAURANT" -> selectors.add("nwr" + nameFilter
                    + "[\"amenity\"~\"^(restaurant|food_court|fast_food|bbq)$\"]" + around + ";");
            case "CAFE" -> selectors.add("nwr" + nameFilter
                    + "[\"amenity\"=\"cafe\"]" + around + ";");
            case "ACCOMMODATION" -> selectors.add("nwr" + nameFilter
                    + "[\"tourism\"~\"^(hotel|guest_house|hostel|chalet|resort|motel|apartment)$\"]"
                    + around + ";");
            default -> {
                selectors.add("nwr" + nameFilter
                        + "[\"tourism\"~\"^(attraction|viewpoint|museum|theme_park|gallery|zoo|aquarium|artwork)$\"]"
                        + around + ";");
                selectors.add("nwr" + nameFilter + "[\"attraction\"]" + around + ";");
                selectors.add("nwr" + nameFilter + "[\"historic\"]" + around + ";");
                selectors.add("nwr" + nameFilter
                        + "[\"leisure\"~\"^(park|garden|nature_reserve)$\"]" + around + ";");
                selectors.add("nwr" + nameFilter
                        + "[\"natural\"~\"^(waterfall|peak|cave|beach|hot_spring)$\"]" + around + ";");
            }
        }

        // Server timeout khớp thời gian chờ phía client: quá tải thì máy chủ trả remark sớm thay vì treo.
        return "[out:json][timeout:8];\n(\n" + String.join("\n", selectors)
                + "\n);\nout center qt;";
    }

    private boolean matchesOsmCategory(Map tags, String category) {
        String amenity = Objects.toString(tags.get("amenity"), "").toLowerCase(Locale.ROOT);
        String tourism = Objects.toString(tags.get("tourism"), "").toLowerCase(Locale.ROOT);
        String leisure = Objects.toString(tags.get("leisure"), "").toLowerCase(Locale.ROOT);
        String natural = Objects.toString(tags.get("natural"), "").toLowerCase(Locale.ROOT);

        return switch (category) {
            case "RESTAURANT" -> Set.of("restaurant", "food_court", "fast_food", "bbq").contains(amenity);
            case "CAFE" -> "cafe".equals(amenity);
            case "ACCOMMODATION" -> Set.of(
                    "hotel", "guest_house", "hostel", "chalet", "resort", "motel", "apartment"
            ).contains(tourism);
            default -> Set.of(
                    "attraction", "viewpoint", "museum", "theme_park", "gallery", "zoo", "aquarium", "artwork"
            ).contains(tourism)
                    || tags.containsKey("attraction")
                    || tags.containsKey("historic")
                    || Set.of("park", "garden", "nature_reserve").contains(leisure)
                    || Set.of("waterfall", "peak", "cave", "beach", "hot_spring").contains(natural);
        };
    }

    private boolean containsCandidateName(List<String> candidates, String osmName) {
        String normalizedOsm = normalizePlaceName(osmName);
        return candidates.stream()
                .anyMatch(candidate -> normalizePlaceName(candidate).equals(normalizedOsm));
    }

    /**
     * Tên trùng khớp sau khi bỏ dấu, hoặc tên này chứa trọn tên kia (vd. "Thiền viện Trúc Lâm" và
     * "Thiền viện Trúc Lâm Phượng Hoàng"). Phần ngắn hơn phải đủ dài để tránh khớp nhầm tên chung chung.
     */
    private boolean namesMatch(String candidate, String osmName) {
        String a = normalizePlaceName(candidate);
        String b = normalizePlaceName(osmName);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        return shorter.length() >= 8 && shorter.split(" ").length >= 2
                && (" " + longer + " ").contains(" " + shorter + " ");
    }

    private String normalizePlaceName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.replace('đ', 'd')
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String escapeRegexLiteral(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) {
            if ("\\\\.^$|?*+()[]{}".indexOf(c) >= 0) {
                result.append('\\');
            }
            result.append(c);
        }
        return result.toString();
    }

    private String escapeOverpassString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<Map> fetchOverpassElements(String query, String destination, String category) {
        return fetchOverpass(query, destination, category, overpassReadTimeoutMs, overpassTotalTimeoutMs).elements();
    }

    /**
     * Gọi Overpass kiểu "hedged": gửi tới máy chủ đầu; sau {@code overpassHedgeDelayMs} chưa có kết quả
     * (hoặc máy chủ đầu lỗi) thì gửi tiếp tới máy chủ kế tiếp, lấy kết quả có dữ liệu ĐẦU TIÊN.
     * Tổng thời gian chờ không quá {@code totalTimeoutMs} thay vì cộng dồn timeout từng máy chủ (trước đây ~30s).
     * allEndpointsFailed = true chỉ khi không máy chủ nào trả lời hợp lệ; trả lời 0 element nghĩa là khu vực
     * không có POI, không phải sự cố.
     */
    private OverpassFetch fetchOverpass(String query, String destination, String category,
                                        int readTimeoutMs, long totalTimeoutMs) {
        List<String> endpoints = resolveOverpassUrls();
        java.util.concurrent.CompletionService<EndpointResult> completion =
                new java.util.concurrent.ExecutorCompletionService<>(OVERPASS_POOL);
        List<java.util.concurrent.Future<EndpointResult>> futures = new ArrayList<>();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(totalTimeoutMs);
        int started = 0;
        int finished = 0;
        boolean anyEndpointAnswered = false;

        futures.add(completion.submit(callOverpassEndpoint(endpoints.get(started++), query, destination, category, readTimeoutMs)));
        try {
            while (finished < started) {
                long remainingMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) break;
                long waitMs = started < endpoints.size() ? Math.min(overpassHedgeDelayMs, remainingMs) : remainingMs;

                java.util.concurrent.Future<EndpointResult> done =
                        completion.poll(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (done == null) {
                    // Máy chủ hiện tại chậm: gửi song song sang máy chủ kế tiếp (nếu còn).
                    if (started < endpoints.size()) {
                        futures.add(completion.submit(callOverpassEndpoint(
                                endpoints.get(started++), query, destination, category, readTimeoutMs)));
                    }
                    continue;
                }
                finished++;
                EndpointResult result = done.get();
                if (!result.elements().isEmpty()) {
                    return new OverpassFetch(result.elements(), false);
                }
                anyEndpointAnswered |= result.answered();
                // Máy chủ này lỗi/rỗng: thử ngay máy chủ kế tiếp thay vì chờ hết thời gian hedge.
                if (started < endpoints.size()) {
                    futures.add(completion.submit(callOverpassEndpoint(
                            endpoints.get(started++), query, destination, category, readTimeoutMs)));
                }
            }
            if (finished < started) {
                log.warn("Overpass quá {} ms cho '{}'/{}; dừng chờ.", totalTimeoutMs, destination, category);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ex) {
            log.warn("Lỗi khi chờ Overpass cho '{}'/{}: {}", destination, category, safeMessage(ex));
        } finally {
            // Không chờ các lời gọi còn lại; chúng tự kết thúc theo read timeout.
            futures.forEach(future -> future.cancel(true));
        }
        return new OverpassFetch(Collections.emptyList(), !anyEndpointAnswered);
    }

    private java.util.concurrent.Callable<EndpointResult> callOverpassEndpoint(
            String endpoint, String query, String destination, String category, int readTimeoutMs) {
        return () -> {
            try {
                HttpHeaders headers = buildHeaders();
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                String formBody = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

                ResponseEntity<Map> response = createHttpClient(readTimeoutMs).postForEntity(
                        endpoint, new HttpEntity<>(formBody, headers), Map.class);
                Map body = response.getBody();
                if (body == null) {
                    log.warn("Overpass endpoint {} trả response body rỗng", endpoint);
                    return new EndpointResult(Collections.emptyList(), false);
                }

                Object remark = body.get("remark");
                if (remark != null && hasText(String.valueOf(remark))) {
                    // Remark thường là query bị timeout phía server: không coi là thành công.
                    log.warn("Overpass remark tại {} cho '{}': {}", endpoint, destination, remark);
                    return new EndpointResult(Collections.emptyList(), false);
                }

                Object rawElements = body.get("elements");
                if (rawElements instanceof List<?> list && !list.isEmpty()) {
                    log.info("OSM discovery thành công: destination='{}', category='{}', endpoint={}, rawElements={}",
                            destination, category, endpoint, list.size());
                    return new EndpointResult((List<Map>) list, true);
                }
                log.info("Overpass endpoint {} không có elements cho '{}'/{}", endpoint, destination, category);
                return new EndpointResult(Collections.emptyList(), true);
            } catch (HttpStatusCodeException ex) {
                log.warn("Overpass endpoint {} trả HTTP {} cho '{}'/{}: {}",
                        endpoint, ex.getStatusCode().value(), destination, category, safeMessage(ex));
            } catch (Exception ex) {
                log.warn("Gọi Overpass endpoint {} thất bại cho '{}'/{}: {}",
                        endpoint, destination, category, safeMessage(ex));
            }
            return new EndpointResult(Collections.emptyList(), false);
        };
    }

    private record EndpointResult(List<Map> elements, boolean answered) { }

    private record OverpassFetch(List<Map> elements, boolean allEndpointsFailed) { }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, hasText(userAgent)
                ? userAgent.trim()
                : "TravelPlatform-HCMUTE/1.0 (contact: configure-your-contact-email)");
        return headers;
    }


    private List<Place> getFallbackFromDb(
            String destination,
            String category,
            int limit
    ) {
        LinkedHashMap<Long, Place> matching = new LinkedHashMap<>();

        // Ưu tiên truy vấn đúng destination và category.
        // Bao gồm OSM và PARTNER đủ điều kiện công khai.
        List<Place> exact =
                placeRepository.findPublicPlacesByDestinationAndPlaceType(
                        destination,
                        category
                );

        if (exact != null) {
            for (Place place : exact) {
                if (place != null
                        && isDiscoverableProvider(place)
                        && matchesCategory(place, category)
                        && place.getId() != null) {

                    matching.putIfAbsent(place.getId(), place);
                }
            }
        }

        // Bổ sung dữ liệu cũ cùng destination nếu cần.
        List<Place> allForDestination =
                placeRepository.findPublicPlacesByDestination(destination);

        if (allForDestination != null) {
            for (Place place : allForDestination) {
                if (place != null
                        && isDiscoverableProvider(place)
                        && matchesCategory(place, category)
                        && place.getId() != null) {

                    matching.putIfAbsent(place.getId(), place);
                }
            }
        }

        List<Place> result = matching.values()
                .stream()
                .limit(limit)
                .collect(Collectors.toList());

        log.info(
                "DB fallback hoàn tất: destination='{}', category={}, returned={}, providers={}",
                destination,
                category,
                result.size(),
                result.stream()
                        .map(Place::getProvider)
                        .distinct()
                        .collect(Collectors.toList())
        );

        return result;
    }



    /**
     * Cho phép lấy cả địa điểm OSM và PARTNER.
     * Điều kiện Business công khai của PARTNER đã được kiểm tra
     * tại câu truy vấn native trong PlaceRepository.
     */
    private boolean isDiscoverableProvider(Place place) {
        if (place == null || place.getProvider() == null) {
            return false;
        }

        String provider = place.getProvider().trim();

        return "OSM".equalsIgnoreCase(provider)
                || "PARTNER".equalsIgnoreCase(provider);
    }


    private boolean matchesCategory(Place place, String category) {
        String type = place.getPlaceType() == null
                ? ""
                : place.getPlaceType().toUpperCase(Locale.ROOT);
        List<String> tags = place.getCategories() == null
                ? Collections.emptyList()
                : place.getCategories();
        String allTags = String.join(" ", tags).toLowerCase(Locale.ROOT);

        return switch (category) {
            case "RESTAURANT" -> type.contains("RESTAURANT")
                    || allTags.contains("amenity=restaurant")
                    || allTags.contains("amenity=fast_food")
                    || allTags.contains("amenity=food_court")
                    || allTags.contains("amenity=bbq");
            case "CAFE" -> type.contains("CAFE") || allTags.contains("amenity=cafe");
            case "ACCOMMODATION" -> type.contains("ACCOMMODATION")
                    || type.contains("HOTEL")
                    || type.contains("LODGING")
                    || allTags.contains("tourism=hotel")
                    || allTags.contains("tourism=guest_house")
                    || allTags.contains("tourism=hostel")
                    || allTags.contains("tourism=chalet")
                    || allTags.contains("tourism=resort")
                    || allTags.contains("tourism=motel")
                    || allTags.contains("tourism=apartment");
            default -> type.contains("ATTRACTION")
                    || type.contains("TOURIST")
                    || allTags.contains("tourism=attraction")
                    || allTags.contains("tourism=viewpoint")
                    || allTags.contains("tourism=museum")
                    || allTags.contains("historic=")
                    || allTags.contains("natural=")
                    || allTags.contains("leisure=");
        };
    }

    private Place upsertOsmPlace(
            String providerPlaceId,
            String name,
            String address,
            String destination,
            String category,
            Double latitude,
            Double longitude,
            List<String> categories) {

        Optional<Place> existing = placeRepository.findByProviderAndProviderPlaceId("OSM", providerPlaceId);
        Place place = existing.orElseGet(Place::new);
        place.setProvider("OSM");
        place.setProviderPlaceId(providerPlaceId);
        place.setName(name);
        place.setAddress(address);
        place.setDestination(destination);
        place.setPlaceType(category);
        // Place stores coordinates as DECIMAL/BigDecimal in the database.
        // Keep missing OSM coordinates as null instead of inventing defaults.
        place.setLatitude(latitude == null ? null : BigDecimal.valueOf(latitude));
        place.setLongitude(longitude == null ? null : BigDecimal.valueOf(longitude));
        place.setCategories(categories);
        place.setFetchedAt(LocalDateTime.now());
        if (place.getCurrency() == null) {
            place.setCurrency("VND");
        }
        // OSM does not provide reliable room/ticket prices or user ratings. Keep those null.
        return placeRepository.save(place);
    }

    /** Converts tool/UI category names to Place.placeType values stored by this service. */
    public String normalizeCategory(String raw) {
        if (!hasText(raw)) {
            return "ATTRACTION";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "RESTAURANT", "FOOD", "ĂN UỐNG", "EATERY" -> "RESTAURANT";
            case "CAFE", "COFFEE", "COFFEE_SHOP" -> "CAFE";
            case "ACCOMMODATION", "HOTEL", "HOMESTAY", "LODGING", "STAY" -> "ACCOMMODATION";
            case "ATTRACTION", "TOURIST_ATTRACTION", "SIGHTSEEING", "ENTERTAINMENT", "ACTIVITY" -> "ATTRACTION";
            default -> "ATTRACTION";
        };
    }

    /** Builds valid Overpass QL; result limit is applied after parsing. */
    private String buildOverpassQuery(
            String category, double latitude, double longitude, int radiusMeters) {
        String around = String.format(Locale.US, "(around:%d,%.6f,%.6f)",
                radiusMeters, latitude, longitude);
        List<String> selectors = new ArrayList<>();

        switch (category) {
            case "RESTAURANT" -> selectors.add(
                    "nwr[\"amenity\"~\"^(restaurant|food_court|fast_food|bbq)$\"][\"name\"]" + around + ";");
            case "CAFE" -> selectors.add(
                    "nwr[\"amenity\"=\"cafe\"][\"name\"]" + around + ";");
            case "ACCOMMODATION" -> selectors.add(
                    "nwr[\"tourism\"~\"^(hotel|guest_house|hostel|chalet|resort|motel|apartment)$\"][\"name\"]"
                            + around + ";");
            default -> {
                selectors.add("nwr[\"tourism\"~\"^(attraction|viewpoint|museum|theme_park|gallery|zoo|aquarium|artwork)$\"][\"name\"]"
                        + around + ";");
                selectors.add("nwr[\"attraction\"][\"name\"]" + around + ";");
                selectors.add("nwr[\"historic\"~\"^(monument|memorial|castle|ruins|archaeological_site)$\"][\"name\"]"
                        + around + ";");
                selectors.add("nwr[\"leisure\"~\"^(park|garden|nature_reserve)$\"][\"name\"]" + around + ";");
                selectors.add("nwr[\"natural\"~\"^(waterfall|peak|cave|beach|hot_spring)$\"][\"name\"]" + around + ";");
            }
        }

        return "[out:json][timeout:15];\n(\n" + String.join("\n", selectors)
                + "\n);\nout center qt;";
    }

    private Double extractLatitude(Map element) {
        if (element.get("lat") instanceof Number value) {
            return value.doubleValue();
        }
        if (element.get("center") instanceof Map center && center.get("lat") instanceof Number value) {
            return value.doubleValue();
        }
        return null;
    }

    private Double extractLongitude(Map element) {
        if (element.get("lon") instanceof Number value) {
            return value.doubleValue();
        }
        if (element.get("center") instanceof Map center && center.get("lon") instanceof Number value) {
            return value.doubleValue();
        }
        return null;
    }

    private String buildOsmAddress(Map tags, String fallbackDestination) {
        List<String> parts = new ArrayList<>();
        addTag(parts, tags, "addr:housenumber");
        addTag(parts, tags, "addr:street");
        addTag(parts, tags, "addr:suburb");
        addTag(parts, tags, "addr:district");
        addTag(parts, tags, "addr:city");
        if (parts.isEmpty() && hasText(fallbackDestination)) {
            parts.add(fallbackDestination);
        }
        return String.join(", ", parts);
    }

    private void addTag(List<String> target, Map tags, String key) {
        Object value = tags.get(key);
        if (value != null && hasText(String.valueOf(value))) {
            target.add(String.valueOf(value).trim());
        }
    }

    private List<String> extractOsmCategories(Map tags) {
        List<String> categories = new ArrayList<>();
        for (String key : List.of(
                "tourism", "amenity", "cuisine", "historic", "natural", "leisure", "shop")) {
            Object value = tags.get(key);
            if (value != null && hasText(String.valueOf(value))) {
                categories.add(key + "=" + value);
            }
        }
        // Địa điểm có bài Wikipedia/Wikidata thường là nơi nổi tiếng: dùng làm tín hiệu "độ nổi tiếng" khi xếp hạng.
        if (hasText(String.valueOf(tags.getOrDefault("wikidata", "")))
                || hasText(String.valueOf(tags.getOrDefault("wikipedia", "")))) {
            categories.add(NOTABLE_CATEGORY);
        }
        return categories;
    }

    private static boolean isValidCoordinates(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0
                && longitude >= -180.0 && longitude <= 180.0;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message;
    }

    private record CachedPlaces(List<Place> places, Instant expiresAt) { }
    private record CachedCoordinates(double[] coordinates, Instant expiresAt) { }
}