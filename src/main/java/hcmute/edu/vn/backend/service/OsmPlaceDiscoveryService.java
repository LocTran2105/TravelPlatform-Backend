package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.repository.PlaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({"unchecked", "rawtypes"})
public class OsmPlaceDiscoveryService {

    private static final String DEFAULT_PHOTON_URL = "https:" + "//photon.komoot.io/api/";
    private static final String DEFAULT_OVERPASS_URL = "https:" + "//overpass-api.de/api/interpreter";

    private final PlaceRepository placeRepository;

    @Value("${osm.photon.url:}")
    private String photonUrl;

    @Value("${osm.overpass.url:}")
    private String overpassUrl;

    @Value("${osm.user-agent:TravelPlatform-HCMUTE/1.0 (contact: support@travelplatform.vn)}")
    private String userAgent;

    @Value("${osm.cache.ttl-seconds:21600}")
    private long cacheTtlSeconds;

    private final Map discoveryCache = new ConcurrentHashMap();
    private final Map geocodeCache = new ConcurrentHashMap();

    private RestTemplate createHttpClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(12000);
        return new RestTemplate(factory);
    }

    private String resolvePhotonUrl() {
        if (photonUrl != null && !photonUrl.isBlank() && !photonUrl.contains("[")) {
            return photonUrl.trim();
        }
        return DEFAULT_PHOTON_URL;
    }

    private String resolveOverpassUrl() {
        if (overpassUrl != null && !overpassUrl.isBlank() && !overpassUrl.contains("[")) {
            return overpassUrl.trim();
        }
        return DEFAULT_OVERPASS_URL;
    }

    public Optional resolveDestinationCoordinates(String destination) {
        if (destination == null || destination.isBlank()) return Optional.empty();
        String normalizedKey = destination.trim().toLowerCase(Locale.ROOT);
        if (geocodeCache.containsKey(normalizedKey)) {
            return Optional.of(geocodeCache.get(normalizedKey));
        }

        try {
            URI uri = UriComponentsBuilder.fromUriString(resolvePhotonUrl())
                    .queryParam("q", destination.trim())
                    .queryParam("lat", 14.0583)
                    .queryParam("lon", 108.2772)
                    .queryParam("zoom", 5)
                    .queryParam("limit", 1)
                    .build()
                    .encode()
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, userAgent);
            ResponseEntity res = createHttpClient().exchange(uri, HttpMethod.GET, new HttpEntity(headers), Map.class);
            Map body = (Map) res.getBody();

            if (body != null && body.get("features") instanceof List features && !features.isEmpty()) {
                Map first = (Map) features.get(0);
                Map geometry = (Map) first.get("geometry");
                List coords = (List) geometry.get("coordinates");
                double lon = ((Number) coords.get(0)).doubleValue();
                double lat = ((Number) coords.get(1)).doubleValue();
                double[] latLon = new double[]{lat, lon};
                geocodeCache.put(normalizedKey, latLon);
                return Optional.of(latLon);
            }
        } catch (Exception e) {
            log.warn("Không thể phân giải tọa độ qua Photon cho '{}': {}", destination, e.getMessage());
        }
        return Optional.empty();
    }

    public List discoverAndPersistPlaces(String destination, String category, int radiusMeters, int limit) {
        String normDest = destination == null ? "" : destination.trim();
        String normCat = normalizeCategory(category);
        int safeRadius = Math.min(Math.max(radiusMeters, 1000), 15000);
        int safeLimit = Math.min(Math.max(limit, 1), 20);

        String cacheKey = normDest.toLowerCase(Locale.ROOT) + "|" + normCat + "|" + safeRadius;
        CachedPlaces cached = (CachedPlaces) discoveryCache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt)) {
            return (List) cached.places.stream().limit(safeLimit).collect(Collectors.toList());
        }

        Optional centerOpt = resolveDestinationCoordinates(normDest);
        if (centerOpt.isEmpty()) {
            return getFallbackFromDb(normDest, normCat, safeLimit);
        }

        double[] center = (double[]) centerOpt.get();
        String overpassQuery = buildOverpassQuery(normCat, center[0], center[1], safeRadius, safeLimit * 2);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.USER_AGENT, userAgent);
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String formBody = "data=" + java.net.URLEncoder.encode(overpassQuery, java.nio.charset.StandardCharsets.UTF_8);
            ResponseEntity response = createHttpClient().postForEntity(
                    resolveOverpassUrl(), new HttpEntity(formBody, headers), Map.class);

            Map body = (Map) response.getBody();
            if (body != null && body.get("elements") instanceof List elements) {
                List persisted = new ArrayList();
                for (Object elemObj : elements) {
                    Map elem = (Map) elemObj;
                    Map tags = (Map) elem.get("tags");
                    if (tags == null || !tags.containsKey("name")) continue;

                    String name = String.valueOf(tags.get("name")).trim();
                    if (name.isBlank()) continue;

                    String osmType = String.valueOf(elem.getOrDefault("type", "node"));
                    String osmId = String.valueOf(elem.get("id"));
                    String providerPlaceId = osmType + "/" + osmId;

                    Double lat = extractLat(elem);
                    Double lon = extractLon(elem);
                    if (lat == null || lon == null) continue;

                    String address = buildOsmAddress(tags, normDest);
                    List tagList = extractOsmCategories(tags);

                    Place place = upsertOsmPlace(providerPlaceId, name, address, normDest, normCat, lat, lon, tagList);
                    persisted.add(place);
                    if (persisted.size() == safeLimit) break;
                }

                if (!persisted.isEmpty()) {
                    discoveryCache.put(cacheKey, new CachedPlaces(persisted, Instant.now().plusSeconds(cacheTtlSeconds)));
                    return persisted;
                }
            }
        } catch (Exception ex) {
            log.warn("Overpass API gặp lỗi hoặc quá tải cho '{}' ({}): {}. Dùng fallback DB.", normDest, normCat, ex.getMessage());
        }

        return getFallbackFromDb(normDest, normCat, safeLimit);
    }

    private List getFallbackFromDb(String destination, String category, int limit) {
        List byType = placeRepository.findByDestinationIgnoreCaseAndPlaceTypeIgnoreCase(destination, category);
        if (!byType.isEmpty()) {
            return (List) byType.stream().limit(limit).collect(Collectors.toList());
        }
        return (List) placeRepository.findByDestinationIgnoreCase(destination).stream().limit(limit).collect(Collectors.toList());
    }

    private Place upsertOsmPlace(String providerPlaceId, String name, String address, String destination,
                                 String placeType, Double lat, Double lon, List categories) {
        Optional existingOpt = placeRepository.findByProviderAndProviderPlaceId("OSM", providerPlaceId);
        Place place = existingOpt.isPresent() ? (Place) existingOpt.get() : new Place();
        place.setProvider("OSM");
        place.setProviderPlaceId(providerPlaceId);
        place.setName(name);
        place.setAddress(address);
        place.setDestination(destination);
        place.setPlaceType(placeType);
        place.setLatitude(lat);
        place.setLongitude(lon);
        place.setCategories(categories);
        place.setFetchedAt(LocalDateTime.now());
        if (place.getCurrency() == null) place.setCurrency("VND");
        return placeRepository.save(place);
    }

    public String normalizeCategory(String raw) {
        if (raw == null) return "ATTRACTION";
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "RESTAURANT", "FOOD", "ĂN UỐNG" -> "RESTAURANT";
            case "CAFE", "COFFEE" -> "CAFE";
            case "ACCOMMODATION", "HOTEL", "HOMESTAY", "LODGING" -> "ACCOMMODATION";
            default -> "ATTRACTION";
        };
    }

    private String buildOverpassQuery(String category, double lat, double lon, int radius, int limit) {
        String filter = switch (category) {
            case "RESTAURANT" -> "[\"amenity\"~\"restaurant|food_court|fast_food\"]";
            case "CAFE" -> "[\"amenity\"=\"cafe\"]";
            case "ACCOMMODATION" -> "[\"tourism\"~\"hotel|guest_house|hostel|chalet|resort\"]";
            default -> "[\"tourism\"~\"attraction|viewpoint|museum|theme_park|gallery|zoo\"]";
        };
        return String.format(Locale.US,
                "[out:json][timeout:10];(node%s(around:%d,%.6f,%.6f);way%s(around:%d,%.6f,%.6f););out center %d;",
                filter, radius, lat, lon, filter, radius, lat, lon, limit);
    }

    private Double extractLat(Map elem) {
        if (elem.get("lat") instanceof Number n) return n.doubleValue();
        if (elem.get("center") instanceof Map center && center.get("lat") instanceof Number n) return n.doubleValue();
        return null;
    }

    private Double extractLon(Map elem) {
        if (elem.get("lon") instanceof Number n) return n.doubleValue();
        if (elem.get("center") instanceof Map center && center.get("lon") instanceof Number n) return n.doubleValue();
        return null;
    }

    private String buildOsmAddress(Map tags, String fallbackCity) {
        List parts = new ArrayList();
        if (tags.get("addr:housenumber") != null) parts.add(String.valueOf(tags.get("addr:housenumber")));
        if (tags.get("addr:street") != null) parts.add(String.valueOf(tags.get("addr:street")));
        if (tags.get("addr:suburb") != null) parts.add(String.valueOf(tags.get("addr:suburb")));
        if (tags.get("addr:city") != null) parts.add(String.valueOf(tags.get("addr:city")));
        else if (fallbackCity != null && !fallbackCity.isBlank()) parts.add(fallbackCity);
        return String.join(", ", parts);
    }

    private List extractOsmCategories(Map tags) {
        List list = new ArrayList();
        for (String key : List.of("tourism", "amenity", "cuisine", "historic", "natural")) {
            if (tags.get(key) != null) {
                list.add(key + "=" + tags.get(key));
            }
        }
        return list;
    }

    private record CachedPlaces(List places, Instant expiresAt) {}
}