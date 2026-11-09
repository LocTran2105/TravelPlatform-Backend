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
import java.math.BigDecimal;

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

    /** Search broad POIs through Overpass, then fall back to category-safe DB records. */
    public List<Place> discoverAndPersistPlaces(
            String destination, String category, int radiusMeters, int limit) {

        if (!hasText(destination)) {
            return Collections.emptyList();
        }

        String normalizedDestination = destination.trim();
        String normalizedCategory = normalizeCategory(category);
        int safeRadius = Math.min(Math.max(radiusMeters, 1_000), MAX_RADIUS_METERS);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_RESULTS);
        String cacheKey = normalizedDestination.toLowerCase(Locale.ROOT)
                + "|" + normalizedCategory + "|" + safeRadius;

        CachedPlaces cached = discoveryCache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.places().stream().limit(safeLimit).collect(Collectors.toList());
        }
        if (cached != null) {
            discoveryCache.remove(cacheKey, cached);
        }

        Optional<double[]> centerOpt = resolveDestinationCoordinates(normalizedDestination);
        if (centerOpt.isEmpty()) {
            log.warn("Không có tâm tìm kiếm cho '{}'; dùng dữ liệu DB đúng category làm fallback.",
                    normalizedDestination);
            return getFallbackFromDb(normalizedDestination, normalizedCategory, safeLimit);
        }

        double[] center = centerOpt.get();
        String query = buildOverpassQuery(normalizedCategory,
                center[0], center[1], safeRadius);

        List<Map> elements = fetchOverpassElements(query, normalizedDestination, normalizedCategory);
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
        String query = buildNamedPlacesQuery(normalizedCategory, safeNames,
                center[0], center[1], safeRadius);
        List<Map> elements = fetchOverpassElements(
                query, normalizedDestination, normalizedCategory + "_NAMED");

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
                    if (!normalizePlaceName(candidate).equals(normalizePlaceName(osmName))) {
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
            default -> ("tourism".equals(key)
                    && Set.of("attraction", "viewpoint", "museum", "theme_park", "gallery", "zoo",
                    "aquarium", "artwork").contains(value))
                    || "attraction".equals(key)
                    || "historic".equals(key)
                    || ("leisure".equals(key)
                    && Set.of("park", "garden", "nature_reserve").contains(value))
                    || ("natural".equals(key)
                    && Set.of("waterfall", "peak", "cave", "beach", "hot_spring").contains(value));
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

        return "[out:json][timeout:12];\n(\n" + String.join("\n", selectors)
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
        for (String endpoint : resolveOverpassUrls()) {
            try {
                HttpHeaders headers = buildHeaders();
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                String formBody = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

                // Keep each public endpoint attempt bounded, then fail over to the next endpoint.
                ResponseEntity<Map> response = createHttpClient(15_000).postForEntity(
                        endpoint,
                        new HttpEntity<>(formBody, headers),
                        Map.class
                );
                Map body = response.getBody();
                if (body == null) {
                    log.warn("Overpass endpoint {} trả response body rỗng", endpoint);
                    continue;
                }

                Object remark = body.get("remark");
                if (remark != null && hasText(String.valueOf(remark))) {
                    log.warn("Overpass remark tại {} cho '{}': {}", endpoint, destination, remark);
                    // An Overpass remark often means the query timed out; do not treat it as success.
                    continue;
                }

                Object rawElements = body.get("elements");
                if (rawElements instanceof List<?> list && !list.isEmpty()) {
                    log.info("OSM discovery thành công: destination='{}', category='{}', endpoint={}, rawElements={}",
                            destination, category, endpoint, list.size());
                    return (List<Map>) list;
                }

                log.info("Overpass endpoint {} không có elements cho '{}'/{}", endpoint, destination, category);
            } catch (HttpStatusCodeException ex) {
                log.warn("Overpass endpoint {} trả HTTP {} cho '{}'/{}: {}",
                        endpoint, ex.getStatusCode().value(), destination, category, safeMessage(ex));
            } catch (Exception ex) {
                log.warn("Gọi Overpass endpoint {} thất bại cho '{}'/{}: {}",
                        endpoint, destination, category, safeMessage(ex));
            }
        }
        return Collections.emptyList();
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, hasText(userAgent)
                ? userAgent.trim()
                : "TravelPlatform-HCMUTE/1.0 (contact: configure-your-contact-email)");
        return headers;
    }

    private List<Place> getFallbackFromDb(String destination, String category, int limit) {
        LinkedHashMap<Long, Place> matching = new LinkedHashMap<>();
        List<Place> exact = placeRepository.findByDestinationIgnoreCaseAndPlaceTypeIgnoreCase(
                destination, category);
        if (exact != null) {
            for (Place place : exact) {
                if (place != null && matchesCategory(place, category) && place.getId() != null) {
                    matching.putIfAbsent(place.getId(), place);
                }
            }
        }

        List<Place> allForDestination = placeRepository.findByDestinationIgnoreCase(destination);
        if (allForDestination != null) {
            for (Place place : allForDestination) {
                if (place != null && matchesCategory(place, category) && place.getId() != null) {
                    matching.putIfAbsent(place.getId(), place);
                }
            }
        }

        return matching.values().stream().limit(limit).collect(Collectors.toList());
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
        place.setLatitude(
                latitude != null ? BigDecimal.valueOf(latitude) : null
        );

        place.setLongitude(
                longitude != null ? BigDecimal.valueOf(longitude) : null
        );
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
