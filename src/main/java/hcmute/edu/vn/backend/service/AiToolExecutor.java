package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PlaceDiscoveryDTO;
import hcmute.edu.vn.backend.dto.TripContextDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiToolExecutor {

    private static final Set<String> WHITELISTED_TOOLS = Set.of(
            "search_places",
            "search_restaurants",
            "search_accommodations",
            "calculate_budget"
    );

    private final RecommendationService recommendationService;

    @SuppressWarnings("rawtypes")
    public Object executeTool(String functionName, Map args, TripContextDTO canonicalContext) {
        if (!WHITELISTED_TOOLS.contains(functionName)) {
            throw new IllegalArgumentException("Công cụ không nằm trong danh sách cho phép: " + functionName);
        }

        // Luôn ưu tiên destination chuẩn từ DB (Canonical Trip Context), không để Gemini tự ý đổi
        String canonicalDestination = canonicalContext != null && canonicalContext.getDestination() != null
                ? canonicalContext.getDestination()
                : (args != null && args.get("destination") != null ? String.valueOf(args.get("destination")) : "");

        Long tripId = canonicalContext != null ? canonicalContext.getTripId() : null;
        int limit = 5;
        if (args != null && args.get("limit") instanceof Number n) {
            limit = Math.min(Math.max(n.intValue(), 1), 10);
        }

        return switch (functionName) {
            case "search_places" -> {
                String category = (args != null && args.get("category") != null)
                        ? String.valueOf(args.get("category"))
                        : "TOURIST_ATTRACTION";
                List<PlaceDiscoveryDTO> results = recommendationService.discoverAndRankPlaces(
                        tripId, canonicalDestination, category, limit);
                yield results;
            }
            case "search_restaurants" -> {
                String type = (args != null && "CAFE".equalsIgnoreCase(String.valueOf(args.get("type"))))
                        ? "CAFE" : "RESTAURANT";
                yield recommendationService.discoverAndRankPlaces(tripId, canonicalDestination, type, limit);
            }
            case "search_accommodations" ->
                    recommendationService.discoverAndRankPlaces(tripId, canonicalDestination, "ACCOMMODATION", limit);
            case "calculate_budget" -> buildBudgetBreakdown(canonicalContext);
            default -> Collections.emptyList();
        };
    }

    private Map<String, Object> buildBudgetBreakdown(TripContextDTO ctx) {
        if (ctx == null || ctx.getTotalBudget() == null || ctx.getTotalBudget().compareTo(BigDecimal.ZERO) <= 0) {
            return Map.of(
                    "hasBudget", false,
                    "note", "Chuyến đi hiện chưa được thiết lập ngân sách cụ thể."
            );
        }
        BigDecimal total = ctx.getTotalBudget();
        int days = (ctx.getTripDays() != null && !ctx.getTripDays().isEmpty()) ? ctx.getTripDays().size() : 1;
        int members = (ctx.getMemberCount() != null && ctx.getMemberCount() > 0) ? ctx.getMemberCount() : 1;

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