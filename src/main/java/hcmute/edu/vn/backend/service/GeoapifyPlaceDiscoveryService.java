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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Discovers OSM-backed places through Geoapify Places API and stores them in the local places table.
 * Geoapify is the primary broad-search provider; OsmPlaceDiscoveryService remains the fallback.
 *
 * Notes:
 * - The API key is read from environment/application configuration, never hardcoded.
 * - No rating, review count, room price, or ticket price is invented.
 * - Geoapify place_id is used as this provider's stable external identifier.
 * - Geoapify Places API data is based on OpenStreetMap and still inherits OSM coverage limits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({"unchecked", "rawtypes"})
public class GeoapifyPlaceDiscoveryService {

    private static final String DEFAULT_GEOCODING_URL = "https://api.geoapify.com/v1/geocode/search";
    private static final String DEFAULT_PLACES_URL = "https://api.geoapify.com/v2/places";
    private static final int MAX_RADIUS_METERS = 30_000;
    private static final int MAX_RESULTS = 100;
    private static final long DEFAULT_CACHE_TTL_SECONDS = 21_600L;

    private final PlaceRepository placeRepository;

    @Value("${geoapify.api.key:}")
    private String apiKey;

    @Value("${geoapify.geocoding.url:https://api.geoapify.com/v1/geocode/search}")
    private String geocodingUrl;

    @Value("${geoapify.places.url:https://api.geoapify.com/v2/places}")
    private String placesUrl;

    @Value("${geoapify.cache.ttl-seconds:21600}")
    private long cacheTtlSeconds;

    private final ConcurrentMap<String, CachedCoordinates> coordinateCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CachedPlaces> placesCache = new ConcurrentHashMap<>();

    private RestTemplate createHttpClient(int readTimeoutMs) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    /**
     * Finds the center of a Vietnamese destination. Geoapify accepts type=city and countrycode filter.
     * Result order is [latitude, longitude].
     */
    public Optional<double[]> resolveDestinationCoordinates(String destination) {
        if (!hasText(destination) || !hasText(apiKey)) {
            if (!hasText(apiKey)) {
                log.warn("Geoapify API key chưa được cấu hình; không thể geocode destination='{}'", destination);
            }
            return Optional.empty();
        }

        String cacheKey = normalizeText(destination);
        CachedCoordinates cached = coordinateCache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return Optional.of(cached.coordinates().clone());
        }
        if (cached != null) {
            coordinateCache.remove(cacheKey, cached);
        }

        try {
            URI uri = UriComponentsBuilder.fromUriString(resolveGeocodingUrl())
                    .queryParam("text", destination.trim() + ", Vietnam")
                    .queryParam("type", "city")
                    .queryParam("filter", "countrycode:vn")
                    .queryParam("format", "json")
                    .queryParam("lang", "vi")
                    .queryParam("limit", 3)
                    .queryParam("apiKey", apiKey.trim())
                    .build()
                    .encode()
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<Map> response = createHttpClient(10_000).exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map body = response.getBody();
            if (body == null) {
                log.warn("Geoapify Geocoding trả body rỗng cho destination='{}'", destination);
                return Optional.empty();
            }

            Object rawResults = body.get("results");
            if (rawResults instanceof List<?> results) {
                for (Object item : results) {
                    if (!(item instanceof Map result)) continue;
                    if (!(result.get("lat") instanceof Number latValue)
                            || !(result.get("lon") instanceof Number lonValue)) continue;

                    double latitude = latValue.doubleValue();
                    double longitude = lonValue.doubleValue();
                    if (!isValidCoordinates(latitude, longitude)) continue;

                    double[] coordinates = new double[]{latitude, longitude};
                    coordinateCache.put(cacheKey, new CachedCoordinates(
                            coordinates, Instant.now().plusSeconds(Math.max(300L, cacheTtlSeconds))));
                    log.info("Geoapify geocode thành công destination='{}', lat={}, lon={}",
                            destination, latitude, longitude);
                    return Optional.of(coordinates.clone());
                }
            }

            log.warn("Geoapify không tìm thấy tọa độ cho destination='{}'", destination);
        } catch (HttpStatusCodeException ex) {
            log.warn("Geoapify Geocoding trả HTTP {} cho destination='{}'",
                    ex.getStatusCode().value(), destination);
        } catch (Exception ex) {
            // Do not log exception.getMessage(): the geocoding URL contains the API key query parameter.
            log.warn("Geoapify geocoding thất bại cho destination='{}': {}",
                    destination, ex.getClass().getSimpleName());
        }
        return Optional.empty();
    }

    /**
     * Searches OSM-backed POIs by category around a destination, persists valid features,
     * and returns the saved Place entities. Empty results are not fabricated.
     */
    public List<Place> discoverAndPersistPlaces(
            String destination, String category, int radiusMeters, int requestedLimit) {

        if (!hasText(destination)) return Collections.emptyList();
        if (!hasText(apiKey)) {
            log.warn("Bỏ qua Geoapify discovery vì thiếu geoapify.api.key");
            return Collections.emptyList();
        }

        String normalizedDestination = destination.trim();
        String normalizedCategory = normalizeCategory(category);
        int radius = Math.min(Math.max(radiusMeters, 500), MAX_RADIUS_METERS);
        int limit = Math.min(Math.max(requestedLimit, 1), MAX_RESULTS);
        String cacheKey = normalizeText(normalizedDestination) + "|" + normalizedCategory + "|" + radius;

        CachedPlaces cached = placesCache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.places().stream().limit(limit).collect(Collectors.toList());
        }
        if (cached != null) placesCache.remove(cacheKey, cached);

        Optional<double[]> centerOptional = resolveDestinationCoordinates(normalizedDestination);
        if (centerOptional.isEmpty()) return Collections.emptyList();

        double[] center = centerOptional.get();
        List<String> categories = geoapifyCategories(normalizedCategory);
        if (categories.isEmpty()) return Collections.emptyList();

        try {
            Map<String, Object> circleFilter = new LinkedHashMap<>();
            circleFilter.put("type", "circle");
            circleFilter.put("lon", center[1]);
            circleFilter.put("lat", center[0]);
            circleFilter.put("radius", radius);

            Map<String, Object> proximityBias = new LinkedHashMap<>();
            proximityBias.put("type", "proximity");
            proximityBias.put("lon", center[1]);
            proximityBias.put("lat", center[0]);

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("categories", categories);
            requestBody.put("filter", circleFilter);
            requestBody.put("bias", proximityBias);
            requestBody.put("limit", limit);
            requestBody.put("lang", "vi");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            // Geoapify Places API supports x-api-key, keeping the credential out of the URL.
            headers.set("x-api-key", apiKey.trim());

            URI uri = URI.create(resolvePlacesUrl());
            ResponseEntity<Map> response = createHttpClient(15_000).exchange(
                    uri,
                    HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    Map.class
            );

            Map responseBody = response.getBody();
            if (responseBody == null || !(responseBody.get("features") instanceof List<?> features)) {
                log.warn("Geoapify Places trả response không có features cho '{}/{}'",
                        normalizedDestination, normalizedCategory);
                return Collections.emptyList();
            }

            List<Place> savedPlaces = new ArrayList<>();
            Set<String> seenProviderIds = new LinkedHashSet<>();

            for (Object featureObject : features) {
                if (savedPlaces.size() >= limit) break;
                if (!(featureObject instanceof Map feature)) continue;
                if (!(feature.get("properties") instanceof Map properties)) continue;

                String name = firstNonBlank(
                        asString(properties.get("name")),
                        asString(properties.get("address_line1"))
                );
                if (!hasText(name)) continue;

                String providerPlaceId = asString(properties.get("place_id"));
                if (!hasText(providerPlaceId)) {
                    // Avoid unstable IDs based only on name/address. Such features are skipped.
                    log.debug("Geoapify feature '{}' không có place_id, bỏ qua để tránh tạo bản ghi trùng.", name);
                    continue;
                }
                if (!seenProviderIds.add(providerPlaceId)) continue;

                Double latitude = numberToDouble(properties.get("lat"));
                Double longitude = numberToDouble(properties.get("lon"));
                if (!isValidCoordinates(latitude, longitude)) {
                    double[] geometryCoordinates = extractGeometryCoordinates(feature);
                    if (geometryCoordinates != null) {
                        longitude = geometryCoordinates[0];
                        latitude = geometryCoordinates[1];
                    }
                }
                if (!isValidCoordinates(latitude, longitude)) continue;

                String address = firstNonBlank(
                        asString(properties.get("formatted")),
                        joinAddress(properties)
                );
                List<String> placeCategories = extractCategories(properties.get("categories"));

                try {
                    Place place = upsertGeoapifyPlace(
                            providerPlaceId,
                            name,
                            address,
                            normalizedDestination,
                            normalizedCategory,
                            latitude,
                            longitude,
                            placeCategories
                    );
                    savedPlaces.add(place);
                } catch (Exception saveException) {
                    log.warn("Không lưu được Geoapify place '{}': {}",
                            name, saveException.getClass().getSimpleName());
                }
            }

            if (!savedPlaces.isEmpty()) {
                placesCache.put(cacheKey, new CachedPlaces(
                        List.copyOf(savedPlaces),
                        Instant.now().plusSeconds(Math.max(300L, cacheTtlSeconds))
                ));
            }

            log.info("Geoapify discovery hoàn tất: destination='{}', category={}, received={}, saved={}",
                    normalizedDestination, normalizedCategory, features.size(), savedPlaces.size());
            return savedPlaces;

        } catch (HttpStatusCodeException ex) {
            log.warn("Geoapify Places trả HTTP {} cho destination='{}', category='{}'",
                    ex.getStatusCode().value(), normalizedDestination, normalizedCategory);
        } catch (Exception ex) {
            // Do not log URL/header details that could contain credentials.
            log.warn("Geoapify Places thất bại cho destination='{}', category='{}': {}",
                    normalizedDestination, normalizedCategory, ex.getClass().getSimpleName());
        }

        return Collections.emptyList();
    }

    private Place upsertGeoapifyPlace(
            String providerPlaceId,
            String name,
            String address,
            String destination,
            String category,
            Double latitude,
            Double longitude,
            List<String> categories) {

        Optional<Place> existing = placeRepository.findByProviderAndProviderPlaceId(
                "GEOAPIFY", providerPlaceId);
        Place place = existing.orElseGet(Place::new);

        place.setProvider("GEOAPIFY");
        place.setProviderPlaceId(providerPlaceId);
        place.setName(name);
        place.setAddress(address);
        place.setDestination(destination);
        place.setPlaceType(category);
        place.setLatitude(latitude);
        place.setLongitude(longitude);
        place.setCategories(categories);
        place.setFetchedAt(LocalDateTime.now());
        if (place.getCurrency() == null) place.setCurrency("VND");

        // Geoapify Places is OSM-derived; it does not provide reliable live room/ticket prices or reviews.
        return placeRepository.save(place);
    }

    private List<String> geoapifyCategories(String category) {
        return switch (category) {
            case "ACCOMMODATION" -> List.of("accommodation");
            case "RESTAURANT" -> List.of(
                    "catering.restaurant",
                    "catering.fast_food",
                    "catering.food_court"
            );
            case "CAFE" -> List.of("catering.cafe");
            default -> List.of(
                    "tourism.attraction",
                    "tourism.sights",
                    "natural",
                    "leisure.park",
                    "national_park"
            );
        };
    }

    public String normalizeCategory(String raw) {
        if (!hasText(raw)) return "ATTRACTION";
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "RESTAURANT", "FOOD", "ĂN UỐNG", "EATERY" -> "RESTAURANT";
            case "CAFE", "COFFEE", "COFFEE_SHOP" -> "CAFE";
            case "ACCOMMODATION", "HOTEL", "HOMESTAY", "LODGING", "STAY" -> "ACCOMMODATION";
            case "ATTRACTION", "TOURIST_ATTRACTION", "SIGHTSEEING", "ENTERTAINMENT", "ACTIVITY" -> "ATTRACTION";
            default -> "ATTRACTION";
        };
    }

    private String resolveGeocodingUrl() {
        return hasText(geocodingUrl) ? geocodingUrl.trim() : DEFAULT_GEOCODING_URL;
    }

    private String resolvePlacesUrl() {
        return hasText(placesUrl) ? placesUrl.trim() : DEFAULT_PLACES_URL;
    }

    private static List<String> extractCategories(Object rawCategories) {
        if (!(rawCategories instanceof List<?> values)) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (value != null && hasText(String.valueOf(value))) {
                result.add(String.valueOf(value));
            }
        }
        return result;
    }

    private static double[] extractGeometryCoordinates(Map feature) {
        Object geometryObject = feature.get("geometry");
        if (!(geometryObject instanceof Map geometry)
                || !(geometry.get("coordinates") instanceof List<?> coordinates)
                || coordinates.size() < 2
                || !(coordinates.get(0) instanceof Number longitude)
                || !(coordinates.get(1) instanceof Number latitude)) {
            return null;
        }
        return new double[]{longitude.doubleValue(), latitude.doubleValue()};
    }

    private static String joinAddress(Map properties) {
        List<String> parts = new ArrayList<>();
        addPart(parts, properties.get("address_line1"));
        addPart(parts, properties.get("address_line2"));
        if (parts.isEmpty()) {
            addPart(parts, properties.get("street"));
            addPart(parts, properties.get("city"));
            addPart(parts, properties.get("state"));
            addPart(parts, properties.get("country"));
        }
        return String.join(", ", parts);
    }

    private static void addPart(List<String> target, Object raw) {
        if (raw == null || !hasText(String.valueOf(raw))) return;
        String value = String.valueOf(raw).trim();
        if (!target.contains(value)) target.add(value);
    }

    private static String firstNonBlank(String first, String second) {
        return hasText(first) ? first.trim() : (hasText(second) ? second.trim() : null);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static Double numberToDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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

    private record CachedCoordinates(double[] coordinates, Instant expiresAt) { }
    private record CachedPlaces(List<Place> places, Instant expiresAt) { }
}