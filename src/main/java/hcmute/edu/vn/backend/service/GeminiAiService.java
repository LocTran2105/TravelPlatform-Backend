package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.AiChatRequest;
import hcmute.edu.vn.backend.dto.AiChatResponse;
import hcmute.edu.vn.backend.dto.PlaceDiscoveryDTO;
import hcmute.edu.vn.backend.dto.TripContextDTO;
import hcmute.edu.vn.backend.entity.*;
import hcmute.edu.vn.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({"unchecked", "rawtypes"})
public class GeminiAiService {

    private static final String DEFAULT_GEMINI_URL =
            "https:" + "//generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

    @Value("${gemini.api.url:}")
    private String apiUrlBase;

    @Value("${gemini.api.key.primary:}")
    private String primaryKey;

    @Value("${gemini.api.key.fallback_1:}")
    private String fallbackKey1;

    @Value("${gemini.api.key.fallback_2:}")
    private String fallbackKey2;

    @Value("${gemini.api.key.fallback_3:}")
    private String fallbackKey3;

    @Value("${gemini.api.thinking-budget:-1}")
    private int thinkingBudget;

    private final AiToolExecutor toolExecutor;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final PlaceRepository placeRepository;
    private final RecommendationService recommendationService;
    private final TripContextService tripContextService;
    private final AiConversationRepository conversationRepository;
    private final AiMessageRepository messageRepository;

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        // Gemini 2.5 may spend time reasoning before returning a function call.
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }

    private String resolveApiUrl() {
        if (apiUrlBase != null && !apiUrlBase.isBlank() && !apiUrlBase.contains("[")) {
            return apiUrlBase.trim();
        }
        return DEFAULT_GEMINI_URL;
    }

    private String getNextApiKey() {
        // Prefer one configured key. Multiple keys from the same project do not create extra quota.
        if (primaryKey != null && !primaryKey.isBlank()) {
            return primaryKey.trim();
        }
        for (String fallback : Arrays.asList(fallbackKey1, fallbackKey2, fallbackKey3)) {
            if (fallback != null && !fallback.isBlank()) {
                return fallback.trim();
            }
        }
        throw new IllegalStateException("Chưa cấu hình Gemini API Key trong application.properties");
    }

    @Transactional(readOnly = true)
    public Trip getAuthorizedTrip(Long tripId, User currentUser) {
        if (tripId == null || currentUser == null) {
            throw new IllegalArgumentException("Thiếu thông tin chuyến đi hoặc người dùng.");
        }
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến đi với ID: " + tripId));

        boolean isOwner = trip.getUser() != null
                && Objects.equals(trip.getUser().getId(), currentUser.getId());
        if (!isOwner && !Boolean.TRUE.equals(trip.getIsPublic())) {
            throw new AccessDeniedException("Bạn không có quyền truy cập chuyến đi này.");
        }
        return trip;
    }

    public User getCurrentAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new AccessDeniedException("Vui lòng đăng nhập để sử dụng trợ lý AI.");
        }

        String usernameOrEmail = auth.getName();
        Optional<User> byEmail = userRepository.findByEmail(usernameOrEmail);
        if (byEmail.isPresent()) {
            return byEmail.get();
        }

        for (User user : userRepository.findAll()) {
            if (usernameOrEmail.equalsIgnoreCase(user.getEmail())
                    || usernameOrEmail.equalsIgnoreCase(user.getFullName())) {
                return user;
            }
        }
        throw new AccessDeniedException("Không tìm thấy thông tin người dùng.");
    }

    public TripContextDTO buildCanonicalTripContext(Trip trip) {
        List<TripContextDTO.TripDaySummaryDTO> daySummaries = new ArrayList<>();
        if (trip.getTripDays() != null) {
            List<TripDay> sortedDays = new ArrayList<>(trip.getTripDays());
            sortedDays.sort(Comparator.comparing(
                    TripDay::getDayNumber,
                    Comparator.nullsLast(Comparator.naturalOrder())
            ));

            for (TripDay day : sortedDays) {
                List<String> existingPlaces = new ArrayList<>();
                if (day.getDestinations() != null) {
                    for (TripDestination destination : day.getDestinations()) {
                        if (destination != null && destination.getPlaceName() != null) {
                            existingPlaces.add(destination.getPlaceName());
                        }
                    }
                }

                daySummaries.add(TripContextDTO.TripDaySummaryDTO.builder()
                        .dayId(day.getId())
                        .dayNumber(day.getDayNumber())
                        .dayDate(day.getDayDate())
                        .existingPlaces(existingPlaces)
                        .build());
            }
        }

        return TripContextDTO.builder()
                .tripId(trip.getId())
                .title(trip.getTitle())
                .destination(trip.getDestination())
                .startDate(trip.getStartDate())
                .endDate(trip.getEndDate())
                .memberCount(trip.getMemberCount())
                .totalBudget(trip.getTotalBudget())
                .tripDays(daySummaries)
                .build();
    }

    @Transactional(readOnly = true)
    public AiChatResponse getConversationHistory(Long tripId) {
        User currentUser = getCurrentAuthenticatedUser();
        Trip trip = getAuthorizedTrip(tripId, currentUser);

        Optional<AiConversation> convOpt = conversationRepository
                .findByTripIdAndUserId(trip.getId(), currentUser.getId());
        if (convOpt.isEmpty()) {
            return AiChatResponse.builder()
                    .conversationId(null)
                    .phase("ACTIVE")
                    .history(Collections.emptyList())
                    .build();
        }

        AiConversation conversation = convOpt.get();
        List<AiMessage> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        List<AiChatResponse.MessageHistoryDTO> historyDTOs = new ArrayList<>();

        for (AiMessage message : messages) {
            if ("USER".equalsIgnoreCase(message.getSenderType())
                    || "AI".equalsIgnoreCase(message.getSenderType())) {
                historyDTOs.add(AiChatResponse.MessageHistoryDTO.builder()
                        .id(message.getId())
                        .sender("USER".equalsIgnoreCase(message.getSenderType()) ? "user" : "ai")
                        .text(message.getContent())
                        .recommendations(restoreRecommendationsFromActionData(message.getActionData()))
                        .build());
            }
        }

        return AiChatResponse.builder()
                .conversationId(conversation.getId())
                .phase(conversation.getStatus())
                .history(historyDTOs)
                .build();
    }

    /**
     * Load the canonical trip context first. For a place-search request, Gemini proposes
     * candidate names before the backend performs any broad POI search. Candidate names are
     * verified by AiToolExecutor -> RecommendationService -> OsmPlaceDiscoveryService.
     */
    public AiChatResponse processChat(AiChatRequest request) {
        if (request == null || request.getTripId() == null) {
            throw new IllegalArgumentException("Thiếu tripId trong yêu cầu AI Chat.");
        }
        if (request.getUserMessage() == null || request.getUserMessage().isBlank()) {
            throw new IllegalArgumentException("Tin nhắn không được để trống.");
        }

        User currentUser = getCurrentAuthenticatedUser();
        TripContextDTO canonicalContext = tripContextService.loadAuthorizedContext(
                request.getTripId(), currentUser.getId());
        Long authorizedTripId = canonicalContext.getTripId();

        AiConversation conversation = conversationRepository
                .findByTripIdAndUserId(authorizedTripId, currentUser.getId())
                .orElseGet(() -> conversationRepository.save(AiConversation.builder()
                        .tripId(authorizedTripId)
                        .userId(currentUser.getId())
                        .status("ACTIVE")
                        .build()));

        messageRepository.save(AiMessage.builder()
                .conversationId(conversation.getId())
                .senderType("USER")
                .content(request.getUserMessage().trim())
                .build());

        List<AiMessage> recentMessages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        List conversationHistory = new ArrayList();
        int startIdx = Math.max(0, recentMessages.size() - 10);

        for (int i = startIdx; i < recentMessages.size(); i++) {
            AiMessage message = recentMessages.get(i);
            if (message.getContent() != null && !message.getContent().isBlank()) {
                String geminiRole = "USER".equalsIgnoreCase(message.getSenderType()) ? "user" : "model";
                conversationHistory.add(Map.of(
                        "role", geminiRole,
                        "parts", List.of(Map.of("text", message.getContent()))
                ));
            }
        }

        List<PlaceDiscoveryDTO> collectedRecommendations = new ArrayList<>();
        boolean placeDiscoveryRequest = isPlaceDiscoveryIntent(request.getUserMessage());

        // Cold-start friendly flow: discover a broad, real POI list from Geoapify/OSM first.
        // This does not require user-generated records to exist in the local places table.
        if (placeDiscoveryRequest) {
            try {
                collectIntentBasedRecommendations(
                        request.getUserMessage(), canonicalContext, collectedRecommendations);
                log.info("Broad POI discovery trước Gemini: tripId={}, destination='{}', count={}",
                        authorizedTripId, canonicalContext.getDestination(), collectedRecommendations.size());
            } catch (Exception discoveryError) {
                log.warn("Broad POI discovery thất bại cho tripId={}: {}",
                        authorizedTripId, safeExceptionMessage(discoveryError));
            }
        }

        List tools = buildTravelTools();
        String systemPrompt = buildSystemInstruction(canonicalContext)
                + buildDiscoveredPlacesContext(collectedRecommendations);
        int maxToolCalls = 5;
        int currentCall = 0;
        String finalAssistantText = null;
        RestTemplate restTemplate = createRestTemplate();
        String requestApiKey = null;

        try {
            requestApiKey = getNextApiKey();
        } catch (Exception keyError) {
            finalAssistantText = "Chưa cấu hình Gemini API Key hợp lệ. Backend sẽ thử tìm địa điểm từ nguồn OSM/dữ liệu đã lưu nếu đây là yêu cầu tìm địa điểm.";
            log.error("Không có Gemini API Key hợp lệ: {}", keyError.getMessage());
        }

        while (currentCall < maxToolCalls && requestApiKey != null) {
            Map requestBody = new HashMap();
            requestBody.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text", systemPrompt))
            ));
            requestBody.put("contents", conversationHistory);
            requestBody.put("tools", tools);
            requestBody.put("generationConfig", Map.of(
                    "thinkingConfig", Map.of("thinkingBudget", thinkingBudget)
            ));

            // AUTO lets Gemini use a specific-name verification tool when necessary,
            // while broad category discovery has already been performed by the backend.
            requestBody.put("toolConfig", Map.of(
                    "functionCallingConfig", Map.of("mode", "AUTO")
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            try {
                String apiUrl = resolveApiUrl() + "?key=" + requestApiKey;
                ResponseEntity responseEntity = restTemplate.postForEntity(
                        apiUrl,
                        new HttpEntity(requestBody, headers),
                        Map.class
                );
                Map response = (Map) responseEntity.getBody();

                if (response == null || !(response.get("candidates") instanceof List candidates)
                        || candidates.isEmpty()) {
                    throw new IllegalStateException("Phản hồi từ Gemini không có candidates hợp lệ.");
                }

                Map firstCandidate = (Map) candidates.get(0);
                Map content = firstCandidate.get("content") instanceof Map
                        ? (Map) firstCandidate.get("content") : null;
                List parts = content != null && content.get("parts") instanceof List
                        ? (List) content.get("parts") : Collections.emptyList();

                boolean hasFunctionCall = false;
                List functionResponseParts = new ArrayList();

                if (content != null) {
                    // Preserve the model functionCall part in the next Gemini request.
                    conversationHistory.add(content);
                }

                for (Object partObject : parts) {
                    if (!(partObject instanceof Map part)) {
                        continue;
                    }

                    if (part.get("functionCall") instanceof Map functionCall) {
                        hasFunctionCall = true;
                        String functionName = String.valueOf(functionCall.get("name"));
                        if (!isAllowedFunction(functionName)) {
                            log.warn("Gemini yêu cầu function không nằm trong whitelist: {}", functionName);
                            continue;
                        }

                        Map args = functionCall.get("args") instanceof Map
                                ? (Map) functionCall.get("args") : Collections.emptyMap();

                        log.info("Gemini gọi function={} cho tripId={}, destination='{}'",
                                functionName, authorizedTripId, canonicalContext.getDestination());

                        Object toolResult = toolExecutor.executeTool(functionName, args, canonicalContext);
                        addRecommendationResults(collectedRecommendations, toolResult);

                        if ("verify_place_candidates".equals(functionName)) {
                            int verifiedCount = toolResult instanceof List<?> resultList
                                    ? resultList.size() : 0;
                            log.info("Gemini candidate verification hoàn tất: tripId={}, verified={}",
                                    authorizedTripId, verifiedCount);
                        }

                        Map functionResponse = new LinkedHashMap();
                        functionResponse.put("name", functionName);
                        if (functionCall.get("id") != null) {
                            functionResponse.put("id", functionCall.get("id"));
                        }

                        Map toolResponse = new LinkedHashMap();
                        toolResponse.put("destination", canonicalContext.getDestination());
                        toolResponse.put("result", toolResult == null
                                ? Collections.emptyList()
                                : toolResult);
                        functionResponse.put("response", toolResponse);
                        functionResponseParts.add(Map.of("functionResponse", functionResponse));
                    }

                    if (part.get("text") != null) {
                        finalAssistantText = String.valueOf(part.get("text"));
                    }
                }

                if (!hasFunctionCall || functionResponseParts.isEmpty()) {
                    break;
                }

                // Gemini expects function responses as a user turn after the model functionCall turn.
                conversationHistory.add(Map.of(
                        "role", "user",
                        "parts", functionResponseParts
                ));
                currentCall++;

            } catch (HttpStatusCodeException httpEx) {
                int status = httpEx.getStatusCode().value();
                String errorDetail = extractGeminiErrorDetail(httpEx);
                log.error("Gemini API HTTP {}: {}", status, errorDetail);

                if (status == 429) {
                    finalAssistantText = buildGroundedFallbackMessage(
                            canonicalContext, collectedRecommendations,
                            "Gemini đang giới hạn số yêu cầu (HTTP 429).");
                } else if (status == 403) {
                    finalAssistantText = buildGroundedFallbackMessage(
                            canonicalContext, collectedRecommendations,
                            "Gemini đang từ chối quyền truy cập (HTTP 403). Cần kiểm tra API key/quyền của project.");
                } else {
                    finalAssistantText = buildGroundedFallbackMessage(
                            canonicalContext, collectedRecommendations,
                            "Gemini tạm thời không xử lý được yêu cầu (HTTP " + status + ").");
                }
                break;

            } catch (Exception exception) {
                // Do not print the exception stack here: RestTemplate messages can contain
                // the Gemini URL, whose query string carries the API key.
                log.error("Lỗi xử lý AI Chat cho tripId={}: {}",
                        authorizedTripId, safeExceptionMessage(exception));
                finalAssistantText = "Mình gặp lỗi khi xử lý yêu cầu. Backend sẽ giữ lại các địa điểm đã xác minh được (nếu có).";
                break;
            }
        }

        // If Gemini explicitly verified candidate names but none matched OSM, retain the
        // category-discovery result already collected above. Do not run the same search twice.
        // When no result was found, the final response explains the data-source limitation.

        if (currentCall >= maxToolCalls
                && (finalAssistantText == null || finalAssistantText.isBlank())) {
            finalAssistantText = "Mình đã tìm được dữ liệu địa điểm, nhưng cần giới hạn số bước gọi công cụ. Bạn có thể xem các gợi ý bên dưới.";
        }

        if (finalAssistantText == null || finalAssistantText.isBlank()) {
            finalAssistantText = collectedRecommendations.isEmpty()
                    ? "Mình chưa tìm được địa điểm phù hợp từ nguồn dữ liệu hiện có tại "
                    + safeDestination(canonicalContext) + ". Bạn có thể thử một nhóm địa điểm khác."
                    : "Mình đã tìm được " + collectedRecommendations.size()
                    + " địa điểm thực tế tại " + safeDestination(canonicalContext)
                    + " phù hợp để bạn tham khảo. Các thẻ bên dưới có thể kéo vào từng ngày trong Itinerary.";
        }

        if (placeDiscoveryRequest && collectedRecommendations.isEmpty()) {
            finalAssistantText += " Hiện Geoapify/OSM và dữ liệu đã lưu chưa trả về địa điểm phù hợp; "
                    + "mình không tự tạo tọa độ, giá hay đánh giá.";
        }

        String actionDataJson = serializePlaceIdsToJson(collectedRecommendations);
        messageRepository.save(AiMessage.builder()
                .conversationId(conversation.getId())
                .senderType("AI")
                .content(finalAssistantText)
                .actionData(actionDataJson)
                .build());

        return AiChatResponse.builder()
                .conversationId(conversation.getId())
                .assistantMessage(finalAssistantText)
                .phase(conversation.getStatus())
                .recommendations(collectedRecommendations)
                .requiresConfirmation(true)
                .build();
    }

    /** Backend broad-search fallback used only when Gemini candidate verification did not run. */
    private void collectIntentBasedRecommendations(
            String userMessage,
            TripContextDTO context,
            List<PlaceDiscoveryDTO> target) {

        if (!isPlaceDiscoveryIntent(userMessage)
                || context == null
                || context.getDestination() == null
                || context.getDestination().isBlank()) {
            return;
        }

        String normalized = normalizeVietnamese(userMessage);
        boolean accommodation = containsAny(normalized,
                "khach san", "homestay", "nha nghi", "noi luu tru", "cho o");
        boolean cafe = containsAny(normalized,
                "quan ca phe", "cafe", "coffee", "quan nuoc");
        boolean restaurant = containsAny(normalized,
                "nha hang", "quan an", "mon an", "an uong", "am thuc", "quan ngon", "quan noi tieng");
        boolean attraction = containsAny(normalized,
                "tham quan", "check in", "dia diem", "du lich", "kham pha", "di dau", "can di", "thang canh");

        if (accommodation) {
            addRecommendationResults(target, toolExecutor.executeTool(
                    "search_accommodations", Map.of("limit", 6), context));
        }
        if (restaurant) {
            addRecommendationResults(target, toolExecutor.executeTool(
                    "search_restaurants", Map.of("type", "RESTAURANT", "limit", 6), context));
        }
        if (cafe) {
            addRecommendationResults(target, toolExecutor.executeTool(
                    "search_restaurants", Map.of("type", "CAFE", "limit", 6), context));
        }
        if (attraction || (!accommodation && !cafe && !restaurant)) {
            addRecommendationResults(target, toolExecutor.executeTool(
                    "search_places", Map.of("category", "TOURIST_ATTRACTION", "limit", 8), context));
        }
    }

    private boolean isPlaceDiscoveryIntent(String message) {
        String normalized = normalizeVietnamese(message);
        return containsAny(normalized,
                "goi y dia diem", "de xuat dia diem", "tim dia diem", "dia diem",
                "tham quan", "check in", "thang canh", "quan an", "nha hang",
                "quan ca phe", "cafe", "coffee", "khach san", "homestay", "nha nghi",
                "noi luu tru", "cho o", "di dau", "kham pha", "am thuc", "quan ngon");
    }

    private void addRecommendationResults(List<PlaceDiscoveryDTO> target, Object result) {
        if (!(result instanceof List<?> items)) {
            return;
        }

        Set<String> keys = new HashSet<>();
        for (PlaceDiscoveryDTO existing : target) {
            keys.add(recommendationKey(existing));
        }

        for (Object item : items) {
            if (item instanceof PlaceDiscoveryDTO dto
                    && dto.getName() != null
                    && !dto.getName().isBlank()) {
                if (keys.add(recommendationKey(dto))) {
                    target.add(dto);
                }
            }
        }
    }

    private String recommendationKey(PlaceDiscoveryDTO dto) {
        if (dto.getPlaceId() != null) {
            return "id:" + dto.getPlaceId();
        }
        return String.valueOf(dto.getName()).trim().toLowerCase(Locale.ROOT)
                + "|" + String.valueOf(dto.getLatitude())
                + "|" + String.valueOf(dto.getLongitude());
    }

    private boolean isAllowedFunction(String name) {
        return Set.of(
                "search_places",
                "search_restaurants",
                "search_accommodations",
                "verify_place_candidates",
                "calculate_budget"
        ).contains(name);
    }

    private String buildGroundedFallbackMessage(
            TripContextDTO context,
            List<PlaceDiscoveryDTO> recommendations,
            String reason) {
        if (recommendations != null && !recommendations.isEmpty()) {
            return reason + " Tuy nhiên, backend đã tìm được "
                    + recommendations.size() + " địa điểm tại " + safeDestination(context)
                    + " từ OpenStreetMap/database. Các thẻ bên dưới vẫn có thể được sử dụng.";
        }
        return reason + " Backend sẽ thử nguồn tìm kiếm OSM/dữ liệu đã lưu nếu phù hợp với yêu cầu.";
    }

    private String safeExceptionMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message
                .replaceAll("(?i)([?&]key=)[^&\\s]+", "$1[REDACTED]")
                .replaceAll("(?i)AIza[0-9A-Za-z_-]{20,}", "[REDACTED_KEY]")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractGeminiErrorDetail(HttpStatusCodeException exception) {
        String body = exception.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return exception.getStatusText();
        }
        String sanitized = body.replaceAll("(?i)AIza[0-9A-Za-z_-]{20,}", "[REDACTED_KEY]")
                .replaceAll("\\s+", " ")
                .trim();
        return sanitized.length() > 700 ? sanitized.substring(0, 700) : sanitized;
    }

    private static String normalizeVietnamese(String value) {
        if (value == null) {
            return "";
        }
        String normalized = java.text.Normalizer.normalize(
                        value.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.replace('đ', 'd').replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizePromptValue(String value) {
        return value == null
                ? ""
                : value.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim();
    }

    private static String safeDestination(TripContextDTO context) {
        return context != null
                && context.getDestination() != null
                && !context.getDestination().isBlank()
                ? context.getDestination()
                : "điểm đến của bạn";
    }

    private String serializePlaceIdsToJson(List<PlaceDiscoveryDTO> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) {
            return null;
        }

        List<String> idStrings = new ArrayList<>();
        for (PlaceDiscoveryDTO dto : recommendations) {
            if (dto != null && dto.getPlaceId() != null) {
                idStrings.add(String.valueOf(dto.getPlaceId()));
            }
        }
        return idStrings.isEmpty() ? null : "[" + String.join(",", idStrings) + "]";
    }

    private List<PlaceDiscoveryDTO> restoreRecommendationsFromActionData(String actionData) {
        if (actionData == null || actionData.isBlank()) {
            return Collections.emptyList();
        }

        String cleaned = actionData.replace("[", "").replace("]", "").trim();
        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> ids = new ArrayList<>();
        for (String part : cleaned.split(",")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // Ignore malformed legacy action-data entries.
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> orderedIds = new ArrayList<>(new LinkedHashSet<>(ids));
        Map<Long, Place> byId = new HashMap<>();
        for (Place place : placeRepository.findAllById(orderedIds)) {
            byId.put(place.getId(), place);
        }

        List<PlaceDiscoveryDTO> result = new ArrayList<>();
        for (Long id : orderedIds) {
            Place place = byId.get(id);
            if (place != null) {
                PlaceDiscoveryDTO dto = recommendationService.toDiscoveryDTO(place);
                if (dto != null) {
                    result.add(dto);
                }
            }
        }
        return result;
    }

    private String buildDiscoveredPlacesContext(List<PlaceDiscoveryDTO> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) {
            return "\n=== KẾT QUẢ TÌM ĐỊA ĐIỂM ===\nBackend chưa nhận được POI phù hợp từ Geoapify/OSM. "
                    + "Không được tự tạo Place Card, địa chỉ, tọa độ, rating hoặc giá.\n";
        }

        StringBuilder context = new StringBuilder(
                "\n=== CÁC ĐỊA ĐIỂM ĐÃ ĐƯỢC BACKEND LẤY TỪ GEOAPIFY/OSM ===\n");
        context.append("Chỉ các địa điểm bên dưới mới được dùng làm Place Card. "
                + "Dữ liệu giá và rating có thể không được nguồn cung cấp.\n");

        for (PlaceDiscoveryDTO place : recommendations) {
            context.append("- ").append(sanitizePromptValue(place.getName()));
            if (place.getAddress() != null) {
                context.append(" | địa chỉ: ").append(sanitizePromptValue(place.getAddress()));
            }
            if (place.getCategory() != null) {
                context.append(" | loại: ").append(sanitizePromptValue(place.getCategory()));
            }
            if (place.getLatitude() != null && place.getLongitude() != null) {
                context.append(" | tọa độ: ").append(place.getLatitude())
                        .append(", ").append(place.getLongitude());
            }
            if (place.getRating() != null) {
                context.append(" | rating: ").append(place.getRating());
            }
            if (place.getUserRatingsTotal() != null) {
                context.append(" | số đánh giá: ").append(place.getUserRatingsTotal());
            }
            if (place.getEstimatedPrice() != null) {
                context.append(" | giá tham khảo: ").append(place.getEstimatedPrice()).append(" VND");
            }
            if (place.getMatchReason() != null) {
                context.append(" | lý do: ").append(sanitizePromptValue(place.getMatchReason()));
            }
            context.append("\n");
        }

        context.append("Không được gọi rating, số review hoặc giá là dữ liệu đã xác nhận nếu trường tương ứng bị thiếu.\n");
        return context.toString();
    }

    private String buildSystemInstruction(TripContextDTO context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Bạn là Trợ lý Lập kế hoạch Du lịch AI thông minh của TravelPlatform, giao tiếp bằng tiếng Việt tự nhiên, súc tích.\n");
        prompt.append("=== THÔNG TIN CHUYẾN ĐI HIỆN TẠI (NGUỒN SỰ THẬT DUY NHẤT) ===\n");
        prompt.append("- Điểm đến: ")
                .append(context.getDestination() != null ? context.getDestination() : "Chưa xác định")
                .append("\n");
        prompt.append("- Tên chuyến đi: ")
                .append(context.getTitle() != null ? context.getTitle() : "")
                .append("\n");
        prompt.append("- Ngày bắt đầu: ")
                .append(context.getStartDate() != null ? context.getStartDate() : "Chưa đặt")
                .append("\n");
        prompt.append("- Ngày kết thúc: ")
                .append(context.getEndDate() != null ? context.getEndDate() : "Chưa đặt")
                .append("\n");
        prompt.append("- Số thành viên: ")
                .append(context.getMemberCount() != null ? context.getMemberCount() : 1)
                .append(" người\n");
        prompt.append("- Tổng ngân sách: ")
                .append(context.getTotalBudget() != null
                        ? context.getTotalBudget().toPlainString() + " VNĐ"
                        : "Chưa đặt")
                .append("\n");

        if (context.getTripDays() != null && !context.getTripDays().isEmpty()) {
            prompt.append("- Các ngày trong lịch trình:\n");
            for (TripContextDTO.TripDaySummaryDTO day : context.getTripDays()) {
                prompt.append("  + Ngày ").append(day.getDayNumber())
                        .append(" (").append(day.getDayDate()).append("): ")
                        .append(day.getExistingPlaces() == null || day.getExistingPlaces().isEmpty()
                                ? "Chưa có địa điểm"
                                : String.join(", ", day.getExistingPlaces()))
                        .append("\n");
            }
        }

        prompt.append("\n=== QUY TẮC BẮT BUỘC ===\n");
        prompt.append("1. Điểm đến hiện tại là '").append(context.getDestination())
                .append("'. Tuyệt đối không hỏi lại người dùng muốn đi đâu.\n");
        prompt.append("2. Không hỏi lại ngày đi, ngày về, ngân sách hoặc số người nếu thông tin đã có ở trên.\n");
        prompt.append("3. Với yêu cầu chung như 'gợi ý 3 homestay', 'tìm nhà hàng', 'địa điểm tham quan', hãy ưu tiên danh sách POI thực tế do backend cung cấp trong phần CONTEXT bên dưới; không tự bịa danh sách tên.\n");
        prompt.append("4. Hãy chọn và xếp hạng các địa điểm trong danh sách backend dựa trên điểm đến, ngân sách, lịch trình, khoảng cách và sở thích nếu có.\n");
        prompt.append("5. Chỉ dùng verify_place_candidates khi người dùng nêu đích danh một địa điểm/cơ sở cụ thể cần kiểm tra.\n");
        prompt.append("6. Chỉ địa điểm có trong kết quả backend mới được xem là địa điểm đã xác minh và hiển thị thành Place Card kéo thả được.\n");
        prompt.append("7. Không tự bịa địa chỉ, tọa độ, ID, rating, số review, giá phòng hoặc giá vé.\n");
        prompt.append("8. OSM/Geoapify không cung cấp bằng chứng rằng khách sạn còn phòng hoặc đang hoạt động; không khẳng định điều đó nếu chưa có nguồn khác.\n");
        prompt.append("9. Nếu rating hoặc estimatedPrice không có dữ liệu, hãy ghi chưa có dữ liệu; không tự điền 0.\n");
        prompt.append("10. Người dùng phải chủ động kéo thẻ hoặc bấm Thêm để ghi địa điểm vào Itinerary; không tự ý sửa lịch trình.\n");
        return prompt.toString();
    }

    private List buildTravelTools() {
        Map searchPlaces = new HashMap();
        searchPlaces.put("name", "search_places");
        searchPlaces.put("description",
                "Tìm POI thực tế theo category tại điểm đến của trip từ Geoapify Places API (dữ liệu OSM-backed), sau đó backend lưu vào places.");
        searchPlaces.put("parameters", Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "category", Map.of("type", "STRING",
                                "description", "Loại địa điểm: TOURIST_ATTRACTION/ATTRACTION, RESTAURANT, CAFE, ACCOMMODATION"),
                        "limit", Map.of("type", "INTEGER",
                                "description", "Số lượng kết quả tối đa, từ 1 đến 8")
                )
        ));

        Map searchRestaurants = new HashMap();
        searchRestaurants.put("name", "search_restaurants");
        searchRestaurants.put("description",
                "Tìm nhà hàng, quán ăn hoặc quán cà phê thực tế tại điểm đến của chuyến đi qua Geoapify Places API.");
        searchRestaurants.put("parameters", Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "type", Map.of("type", "STRING", "description", "RESTAURANT hoặc CAFE"),
                        "limit", Map.of("type", "INTEGER", "description", "Số lượng kết quả, mặc định 5")
                )
        ));

        Map searchAccommodations = new HashMap();
        searchAccommodations.put("name", "search_accommodations");
        searchAccommodations.put("description",
                "Tìm khách sạn, homestay, nhà nghỉ và nơi lưu trú thực tế tại điểm đến của chuyến đi qua Geoapify Places API.");
        searchAccommodations.put("parameters", Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "limit", Map.of("type", "INTEGER", "description", "Số lượng kết quả, mặc định 5")
                )
        ));

        Map verifyPlaceCandidates = new HashMap();
        verifyPlaceCandidates.put("name", "verify_place_candidates");
        verifyPlaceCandidates.put("description",
                "Chỉ dùng khi người dùng nêu một địa điểm/cơ sở cụ thể và muốn backend xác minh đúng tên đó. "
                        + "Backend sẽ đối chiếu tên, loại và tọa độ qua nguồn OSM. "
                        + "Với yêu cầu chung tìm homestay/nhà hàng/điểm tham quan, hãy dùng search_accommodations, "
                        + "search_restaurants hoặc search_places thay vì tự sinh tên.");
        verifyPlaceCandidates.put("parameters", Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "category", Map.of("type", "STRING",
                                "description", "Một trong: RESTAURANT, CAFE, ACCOMMODATION, TOURIST_ATTRACTION"),
                        "candidateNames", Map.of(
                                "type", "ARRAY",
                                "items", Map.of("type", "STRING"),
                                "description", "Tối đa 8 tên địa điểm cụ thể do AI suy luận. Không thêm địa chỉ hoặc tọa độ."
                        ),
                        "limit", Map.of("type", "INTEGER",
                                "description", "Số kết quả đã xác minh tối đa, từ 1 đến 10")
                ),
                "required", List.of("category", "candidateNames")
        ));

        Map calculateBudget = new HashMap();
        calculateBudget.put("name", "calculate_budget");
        calculateBudget.put("description",
                "Tính toán phân bổ ngân sách gợi ý theo ngày và theo đầu người dựa trên tổng ngân sách chuyến đi.");
        calculateBudget.put("parameters", Map.of(
                "type", "OBJECT",
                "properties", Collections.emptyMap()
        ));

        return List.of(Map.of("functionDeclarations", List.of(
                searchPlaces,
                searchRestaurants,
                searchAccommodations,
                verifyPlaceCandidates,
                calculateBudget
        )));
    }
}
