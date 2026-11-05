package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PlaceDiscoveryDTO;
import hcmute.edu.vn.backend.dto.PlaceRecommendationDTO;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripPreference;
import hcmute.edu.vn.backend.repository.TripPreferenceRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Discovers places from Geoapify first, falls back to direct OSM discovery,
 * then ranks only on attributes that actually exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private static final int SEARCH_RADIUS_METERS = 8_000;
    private static final int MAX_RESULTS = 30;
    private static final int MIN_DISCOVERY_BATCH = 12;

    private final TripRepository tripRepository;
    private final TripPreferenceRepository tripPreferenceRepository;
    private final OsmPlaceDiscoveryService osmPlaceDiscoveryService;
    private final GeoapifyPlaceDiscoveryService geoapifyPlaceDiscoveryService;

    @Transactional
    public List<PlaceDiscoveryDTO> discoverAndRankPlaces(
            Long tripId, String explicitDestination, String category, int requestedLimit) {

        int limit = Math.max(1, Math.min(requestedLimit, MAX_RESULTS));
        Trip trip = tripId == null ? null : tripRepository.findById(tripId).orElse(null);
        String destination = trip != null && hasText(trip.getDestination())
                ? trip.getDestination().trim()
                : trimToNull(explicitDestination);
        if (destination == null) return Collections.emptyList();

        String normalizedCategory = geoapifyPlaceDiscoveryService.normalizeCategory(
                hasText(category) ? category.trim() : "TOURIST_ATTRACTION");
        int discoveryLimit = Math.max(limit * 2, MIN_DISCOVERY_BATCH);

        // Geoapify Places API is the primary broad-search source.
        List<Place> discovered = geoapifyPlaceDiscoveryService.discoverAndPersistPlaces(
                destination,
                normalizedCategory,
                SEARCH_RADIUS_METERS,
                discoveryLimit
        );

        // Keep the existing direct OSM implementation as a fallback if Geoapify is unavailable
        // or returns no usable records. The fallback does not manufacture any places.
        if (discovered == null || discovered.isEmpty()) {
            log.warn("Geoapify không có kết quả cho '{}'/{}. Chuyển sang OSM fallback.",
                    destination, normalizedCategory);
            discovered = osmPlaceDiscoveryService.discoverAndPersistPlaces(
                    destination,
                    normalizedCategory,
                    SEARCH_RADIUS_METERS,
                    discoveryLimit
            );
        }

        if (discovered == null || discovered.isEmpty()) {
            log.warn("Không tìm thấy POI cho destination='{}', category='{}' từ Geoapify/OSM.",
                    destination, normalizedCategory);
            return Collections.emptyList();
        }

        Optional<double[]> center = geoapifyPlaceDiscoveryService.resolveDestinationCoordinates(destination);
        if (center.isEmpty()) {
            center = osmPlaceDiscoveryService.resolveDestinationCoordinates(destination);
        }

        List<String> preferenceTags = loadPreferenceTags(tripId);
        final String categoryForScoring = normalizedCategory;
        final double[] destinationCenter = center.orElse(null);

        Map<String, Place> uniquePlaces = new LinkedHashMap<>();
        for (Place place : discovered) {
            if (place == null || !hasText(place.getName())) continue;
            uniquePlaces.putIfAbsent(buildDeduplicationKey(place), place);
        }

        return uniquePlaces.values().stream()
                .map(place -> scorePlace(
                        place, trip, preferenceTags, destinationCenter, categoryForScoring))
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(PlaceDiscoveryDTO::getMatchScore,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PlaceDiscoveryDTO::getName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Verifies an explicitly named list against the existing OSM named-place flow.
     * Broad category discovery should use discoverAndRankPlaces(), which now uses Geoapify first.
     */
    @Transactional
    public List<PlaceDiscoveryDTO> discoverNamedPlaces(
            Long tripId,
            String explicitDestination,
            String category,
            List<String> candidateNames,
            int requestedLimit) {

        int limit = Math.max(1, Math.min(requestedLimit, 10));
        Trip trip = tripId == null ? null : tripRepository.findById(tripId).orElse(null);
        String destination = trip != null && hasText(trip.getDestination())
                ? trip.getDestination().trim()
                : trimToNull(explicitDestination);
        if (destination == null || candidateNames == null || candidateNames.isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedCategory = osmPlaceDiscoveryService.normalizeCategory(category);
        List<Place> matched = osmPlaceDiscoveryService.discoverNamedPlaces(
                destination, normalizedCategory, candidateNames, 12_000, limit);

        if (matched == null || matched.isEmpty()) return Collections.emptyList();

        Optional<double[]> center = geoapifyPlaceDiscoveryService.resolveDestinationCoordinates(destination);
        if (center.isEmpty()) {
            center = osmPlaceDiscoveryService.resolveDestinationCoordinates(destination);
        }

        List<String> preferenceTags = loadPreferenceTags(tripId);
        final double[] destinationCenter = center.orElse(null);

        return matched.stream()
                .filter(Objects::nonNull)
                .map(place -> scorePlace(
                        place, trip, preferenceTags, destinationCenter, normalizedCategory))
                .filter(Objects::nonNull)
                .limit(limit)
                .collect(Collectors.toList());
    }

    private List<String> loadPreferenceTags(Long tripId) {
        if (tripId == null) return Collections.emptyList();
        // Kept consistent with the supplied project implementation.
        // Confirm this lookup if TripPreference uses a separate trip_id foreign key as its identifier.
        TripPreference preference = tripPreferenceRepository.findById(tripId).orElse(null);
        if (preference == null || preference.getPreferenceTags() == null) {
            return Collections.emptyList();
        }
        return preference.getPreferenceTags().stream()
                .filter(RecommendationService::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
    }

    private PlaceDiscoveryDTO scorePlace(
            Place place,
            Trip trip,
            List<String> preferenceTags,
            double[] destinationCenter,
            String category) {

        double weightedScore = 0.0;
        double activeWeight = 0.0;
        List<String> reasons = new ArrayList<>();

        // 1. Preference match: score only when preferences and source categories exist.
        List<String> placeCategories = place.getCategories();
        if (!preferenceTags.isEmpty() && placeCategories != null && !placeCategories.isEmpty()) {
            long matched = preferenceTags.stream()
                    .filter(tag -> placeCategories.stream()
                            .anyMatch(value -> containsIgnoreCase(value, tag)))
                    .count();
            weightedScore += 0.35 * ((double) matched / preferenceTags.size());
            activeWeight += 0.35;
            if (matched > 0) reasons.add("Phù hợp với sở thích của bạn");
        }

        // 2. Rating: only use a valid source-provided rating. Geoapify/OSM often has none.
        BigDecimal rawRating = place.getRating();
        BigDecimal rating = rawRating != null
                && rawRating.compareTo(BigDecimal.ZERO) > 0
                && rawRating.compareTo(new BigDecimal("5.0")) <= 0
                ? rawRating : null;
        if (rating != null) {
            weightedScore += 0.20 * (rating.doubleValue() / 5.0);
            activeWeight += 0.20;
            reasons.add("Đánh giá " + rating.stripTrailingZeros().toPlainString() + "/5");
        }

        // 3. Distance from destination center.
        Double distanceMeters = null;
        if (isValidCoordinates(destinationCenter)
                && isValidCoordinates(place.getLatitude(), place.getLongitude())) {
            distanceMeters = haversineMeters(
                    destinationCenter[0], destinationCenter[1],
                    place.getLatitude(), place.getLongitude());
            double proximityScore = Math.max(0.0, 1.0 - distanceMeters / SEARCH_RADIUS_METERS);
            weightedScore += 0.25 * proximityScore;
            activeWeight += 0.25;
            reasons.add(String.format(Locale.ROOT,
                    "Cách khu vực trung tâm khoảng %.1f km", distanceMeters / 1000.0));
        }

        // 4. Budget: a reference estimate only, never a live quote.
        BigDecimal rawEstimatedCost = place.getEstimatedCostMin();
        BigDecimal estimatedCost = rawEstimatedCost != null
                && rawEstimatedCost.compareTo(BigDecimal.ZERO) > 0 ? rawEstimatedCost : null;
        BigDecimal dailyPerPersonBudget = calculateDailyPerPersonBudget(trip);
        if (estimatedCost != null && dailyPerPersonBudget != null
                && dailyPerPersonBudget.compareTo(BigDecimal.ZERO) > 0) {
            double ratio = estimatedCost.doubleValue() / dailyPerPersonBudget.doubleValue();
            double budgetScore = ratio <= 0.25 ? 1.0
                    : ratio <= 0.50 ? 0.75
                    : ratio <= 1.0 ? 0.45 : 0.15;
            weightedScore += 0.20 * budgetScore;
            activeWeight += 0.20;
            if (ratio <= 0.50) {
                reasons.add("Chi phí tham khảo tương đối phù hợp ngân sách ngày/người");
            }
        }

        Double matchScore = activeWeight > 0
                ? Math.round((weightedScore / activeWeight) * 100.0) / 100.0
                : null;
        if (reasons.isEmpty()) {
            reasons.add("Địa điểm được tìm thấy tại "
                    + safeText(place.getDestination(), "điểm đến"));
        }

        boolean validCoordinates = isValidCoordinates(place.getLatitude(), place.getLongitude());
        return PlaceDiscoveryDTO.builder()
                .placeId(place.getId())
                .provider(place.getProvider())
                .providerPlaceId(place.getProviderPlaceId())
                .name(place.getName())
                .address(place.getAddress())
                .destination(place.getDestination())
                .category(category)
                .categories(placeCategories)
                .latitude(validCoordinates ? place.getLatitude() : null)
                .longitude(validCoordinates ? place.getLongitude() : null)
                .rating(rating)
                .userRatingsTotal(place.getUserRatingsTotal())
                .estimatedPrice(estimatedCost)
                .priceType(estimatedCost != null ? "REFERENCE" : "UNKNOWN")
                .distanceMeters(distanceMeters)
                .matchScore(matchScore)
                .matchReason(String.join(" • ", reasons))
                .sourceUrl(null)
                .attribution("© OpenStreetMap contributors; data via Geoapify")
                .build();
    }

    private BigDecimal calculateDailyPerPersonBudget(Trip trip) {
        if (trip == null || trip.getTotalBudget() == null
                || trip.getTotalBudget().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        int members = trip.getMemberCount() == null || trip.getMemberCount() <= 0
                ? 1 : trip.getMemberCount();
        int days = trip.getTripDays() == null ? 0 : trip.getTripDays().size();
        if (days <= 0) return null;

        return trip.getTotalBudget().divide(
                BigDecimal.valueOf((long) members * days), 2, RoundingMode.HALF_UP);
    }

    @Transactional
    public List<PlaceRecommendationDTO> getRecommendationsForTrip(Long tripId) {
        List<PlaceDiscoveryDTO> discovered = discoverAndRankPlaces(
                tripId, null, "TOURIST_ATTRACTION", 20);
        return discovered.stream()
                .map(dto -> PlaceRecommendationDTO.builder()
                        .placeId(dto.getPlaceId())
                        .name(dto.getName())
                        .address(dto.getAddress())
                        .placeType(dto.getCategory())
                        .rating(dto.getRating())
                        .userRatingsTotal(dto.getUserRatingsTotal())
                        .categories(dto.getCategories())
                        .estimatedCostMin(dto.getEstimatedPrice())
                        .estimatedCostMax(dto.getEstimatedPrice())
                        .matchScore(dto.getMatchScore())
                        .matchReason(dto.getMatchReason())
                        .build())
                .collect(Collectors.toList());
    }

    public PlaceDiscoveryDTO toDiscoveryDTO(Place place) {
        if (place == null) return null;
        return scorePlace(place, null, Collections.emptyList(), null, place.getPlaceType());
    }

    private String buildDeduplicationKey(Place place) {
        if (hasText(place.getProvider()) && hasText(place.getProviderPlaceId())) {
            return place.getProvider().trim().toLowerCase(Locale.ROOT)
                    + ":" + place.getProviderPlaceId().trim();
        }
        if (place.getId() != null) return "id:" + place.getId();
        return safeText(place.getName(), "").toLowerCase(Locale.ROOT)
                + "|" + safeText(place.getDestination(), "").toLowerCase(Locale.ROOT);
    }

    private static boolean containsIgnoreCase(String value, String search) {
        return value != null && search != null
                && value.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }

    private static boolean isValidCoordinates(double[] coordinates) {
        return coordinates != null && coordinates.length >= 2
                && isValidCoordinates(coordinates[0], coordinates[1]);
    }

    private static boolean isValidCoordinates(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0
                && longitude >= -180.0 && longitude <= 180.0;
    }

    private static double haversineMeters(
            double lat1, double lon1, double lat2, double lon2) {
        final double earthRadiusMeters = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        return 2.0 * earthRadiusMeters * Math.asin(Math.sqrt(a));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String safeText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }
}
