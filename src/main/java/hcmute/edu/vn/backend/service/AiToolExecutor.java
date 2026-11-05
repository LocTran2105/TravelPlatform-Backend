package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PlaceDiscoveryDTO;
import hcmute.edu.vn.backend.dto.TripContextDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Slf4j
@Component
@RequiredArgsConstructor
public class AiToolExecutor {

    private static final Set<String> WHITELISTED_TOOLS = Set.of(
            "search_places",
            "search_restaurants",
            "search_accommodations",
            "verify_place_candidates",
            "calculate_budget"
    );

    private final RecommendationService recommendationService;

    public Object executeTool(String functionName, Map<?, ?> args, TripContextDTO canonicalContext) {
        if (!WHITELISTED_TOOLS.contains(functionName)) {
            throw new IllegalArgumentException("Công cụ không nằm trong danh sách cho phép: " + functionName);
        }

        // Trip context loaded from DB is authoritative. Gemini cannot change destination.
        String canonicalDestination = canonicalContext != null
                ? canonicalContext.getDestination()
                : null;
        if ((canonicalDestination == null || canonicalDestination.isBlank())
                && args != null && args.get("destination") != null) {
            canonicalDestination = String.valueOf(args.get("destination"));
        }

        Long tripId = canonicalContext != null ? canonicalContext.getTripId() : null;
        int limit = readLimit(args, 5, 10);

        return switch (functionName) {
            case "search_places" -> {
                String category = readString(args, "category", "TOURIST_ATTRACTION");
                yield recommendationService.discoverAndRankPlaces(
                        tripId, canonicalDestination, category, limit);
            }
            case "search_restaurants" -> {
                String type = "CAFE".equalsIgnoreCase(readString(args, "type", "RESTAURANT"))
                        ? "CAFE" : "RESTAURANT";
                yield recommendationService.discoverAndRankPlaces(
                        tripId, canonicalDestination, type, limit);
            }
            case "search_accommodations" -> recommendationService.discoverAndRankPlaces(
                    tripId, canonicalDestination, "ACCOMMODATION", limit);
            case "verify_place_candidates" -> {
                String category = readString(args, "category", "RESTAURANT");
                List<String> candidateNames = readCandidateNames(args);
                if (candidateNames.isEmpty()) {
                    yield Collections.emptyList();
                }
                yield recommendationService.discoverNamedPlaces(
                        tripId, canonicalDestination, category, candidateNames, limit);
            }
            case "calculate_budget" -> buildBudgetBreakdown(canonicalContext);
            default -> Collections.emptyList();
        };
    }

    private int readLimit(Map<?, ?> args, int defaultValue, int maxValue) {
        if (args == null || !(args.get("limit") instanceof Number number)) return defaultValue;
        return Math.max(1, Math.min(number.intValue(), maxValue));
    }

    private String readString(Map<?, ?> args, String key, String defaultValue) {
        if (args == null || args.get(key) == null) return defaultValue;
        String value = String.valueOf(args.get(key)).trim();
        return value.isBlank() ? defaultValue : value;
    }

    private List<String> readCandidateNames(Map<?, ?> args) {
        if (args == null || !(args.get("candidateNames") instanceof List<?> rawNames)) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (Object raw : rawNames) {
            if (raw == null) continue;
            String name = String.valueOf(raw).trim();
            if (!name.isBlank() && name.length() <= 100) unique.add(name);
            if (unique.size() >= 10) break;
        }
        return new ArrayList<>(unique);
    }

    private Map<String, Object> buildBudgetBreakdown(TripContextDTO context) {
        if (context == null || context.getTotalBudget() == null
                || context.getTotalBudget().compareTo(BigDecimal.ZERO) <= 0) {
            return Map.of(
                    "hasBudget", false,
                    "note", "Chuyến đi hiện chưa được thiết lập ngân sách cụ thể."
            );
        }

        BigDecimal total = context.getTotalBudget();
        int days = context.getTripDays() != null && !context.getTripDays().isEmpty()
                ? context.getTripDays().size() : 1;
        int members = context.getMemberCount() != null && context.getMemberCount() > 0
                ? context.getMemberCount() : 1;

        return Map.of(
                "hasBudget", true,
                "totalBudgetVnd", total,
                "days", days,
                "memberCount", members,
                "perDayEstimatedVnd", total.divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_UP),
                "perPersonEstimatedVnd", total.divide(BigDecimal.valueOf(members), 0, RoundingMode.HALF_UP),
                "suggestedAllocation", Map.of(
                        "lodging40Percent", total.multiply(new BigDecimal("0.40")),
                        "food30Percent", total.multiply(new BigDecimal("0.30")),
                        "activities20Percent", total.multiply(new BigDecimal("0.20")),
                        "contingency10Percent", total.multiply(new BigDecimal("0.10"))
                )
        );
    }
}
