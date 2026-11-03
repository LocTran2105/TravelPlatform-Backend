package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PlaceDiscoveryDTO;
import hcmute.edu.vn.backend.dto.PlaceRecommendationDTO;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripPreference;
import hcmute.edu.vn.backend.repository.PlaceRepository;
import hcmute.edu.vn.backend.repository.TripPreferenceRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final TripRepository tripRepository;
    private final TripPreferenceRepository tripPreferenceRepository;
    private final PlaceRepository placeRepository;
    private final OsmPlaceDiscoveryService osmPlaceDiscoveryService;

    @Transactional
    public List<PlaceDiscoveryDTO> discoverAndRankPlaces(Long tripId, String explicitDestination, String category, int limit) {
        Trip trip = tripId != null ? tripRepository.findById(tripId).orElse(null) : null;
        String destination = (trip != null && trip.getDestination() != null && !trip.getDestination().isBlank())
                ? trip.getDestination()
                : explicitDestination;

        if (destination == null || destination.isBlank()) {
            return Collections.emptyList();
        }

        String normCategory = osmPlaceDiscoveryService.normalizeCategory(category);
        List<Place> places = osmPlaceDiscoveryService.discoverAndPersistPlaces(destination, normCategory, 8000, Math.max(limit * 2, 12));
        Optional<double[]> centerOpt = osmPlaceDiscoveryService.resolveDestinationCoordinates(destination);

        TripPreference pref = (tripId != null) ? tripPreferenceRepository.findById(tripId).orElse(null) : null;
        List<String> userTags = (pref != null && pref.getPreferenceTags() != null) ? pref.getPreferenceTags() : Collections.emptyList();

        return places.stream()
                .map(place -> scorePlace(place, trip, userTags, centerOpt.orElse(null), normCategory))
                .sorted(Comparator.comparing(PlaceDiscoveryDTO::getMatchScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private PlaceDiscoveryDTO scorePlace(Place place, Trip trip, List<String> userTags, double[] centerCoords, String category) {
        double weightedSum = 0.0;
        double activeWeight = 0.0;
        List<String> reasons = new ArrayList<>();

        if (!userTags.isEmpty() && place.getCategories() != null && !place.getCategories().isEmpty()) {
            long matched = userTags.stream()
                    .filter(tag -> place.getCategories().stream().anyMatch(c -> c.toLowerCase().contains(tag.toLowerCase())))
                    .count();
            double prefScore = (double) matched / userTags.size();
            weightedSum += 0.30 * prefScore;
            activeWeight += 0.30;
            if (prefScore > 0) reasons.add("Phù hợp sở thích của bạn");
        }

        if (place.getRating() != null && place.getRating().compareTo(BigDecimal.ZERO) > 0) {
            double popScore = Math.min(1.0, place.getRating().doubleValue() / 5.0);
            weightedSum += 0.20 * popScore;
            activeWeight += 0.20;
            reasons.add("Đánh giá " + place.getRating() + "★");
        }

        Double distanceMeters = null;
        if (centerCoords != null && place.getLatitude() != null && place.getLongitude() != null) {
            distanceMeters = haversineMeters(centerCoords[0], centerCoords[1], place.getLatitude(), place.getLongitude());
            double proxScore = Math.max(0.1, 1.0 - (distanceMeters / 10000.0));
            weightedSum += 0.20 * proxScore;
            activeWeight += 0.20;
            double km = Math.round((distanceMeters / 1000.0) * 10.0) / 10.0;
            reasons.add("Cách trung tâm ~" + km + " km");
        }

        if (place.getEstimatedCostMin() != null && place.getEstimatedCostMin().compareTo(BigDecimal.ZERO) > 0
                && trip != null && trip.getTotalBudget() != null && trip.getTotalBudget().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal threshold = trip.getTotalBudget().multiply(new BigDecimal("0.20"));
            double budgetScore = place.getEstimatedCostMin().compareTo(threshold) <= 0 ? 1.0 : 0.4;
            weightedSum += 0.15 * budgetScore;
            activeWeight += 0.15;
            if (budgetScore >= 1.0) reasons.add("Phù hợp ngân sách");
        }

        double timeFitScore = 0.85;
        weightedSum += 0.15 * timeFitScore;
        activeWeight += 0.15;

        double normalizedScore = activeWeight > 0 ? Math.round((weightedSum / activeWeight) * 100.0) / 100.0 : 0.75;
        if (reasons.isEmpty()) {
            reasons.add("Địa điểm thực tế tại " + place.getDestination());
        }

        // SỬA LỖI: Cứng chuỗi do Entity Place không có getPriceType và getSourceUrl
        return PlaceDiscoveryDTO.builder()
                .placeId(place.getId())
                .provider(place.getProvider())
                .providerPlaceId(place.getProviderPlaceId())
                .name(place.getName())
                .address(place.getAddress())
                .destination(place.getDestination())
                .category(category)
                .categories(place.getCategories())
                .latitude(place.getLatitude())
                .longitude(place.getLongitude())
                .rating(place.getRating())
                .userRatingsTotal(place.getUserRatingsTotal())
                .estimatedPrice(place.getEstimatedCostMin())
                .priceType(place.getEstimatedCostMin() != null ? "REFERENCE" : "UNKNOWN")
                .distanceMeters(distanceMeters)
                .matchScore(normalizedScore)
                .matchReason(String.join(" • ", reasons))
                .sourceUrl(null)
                .attribution("© OpenStreetMap contributors")
                .build();
    }

    @Transactional(readOnly = true)
    public List<PlaceRecommendationDTO> getRecommendationsForTrip(Long tripId) {
        List<PlaceDiscoveryDTO> discovered = discoverAndRankPlaces(tripId, null, "TOURIST_ATTRACTION", 20);
        return discovered.stream().map(d -> PlaceRecommendationDTO.builder()
                .placeId(d.getPlaceId())
                .name(d.getName())
                .address(d.getAddress())
                .placeType(d.getCategory())
                .rating(d.getRating())
                .userRatingsTotal(d.getUserRatingsTotal())
                .categories(d.getCategories())
                .estimatedCostMin(d.getEstimatedPrice())
                .estimatedCostMax(d.getEstimatedPrice())
                .matchScore(d.getMatchScore())
                .matchReason(d.getMatchReason())
                .build()).collect(Collectors.toList());
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    public PlaceDiscoveryDTO toDiscoveryDTO(Place place) {
        if (place == null) return null;
        return scorePlace(place, null, Collections.emptyList(), null, place.getPlaceType());
    }
}