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
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({"unchecked", "rawtypes"})
public class GeminiAiService {

    private static final String DEFAULT_GEMINI_URL = "https:" + "//generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

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

    private final AiToolExecutor toolExecutor;
    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final PlaceRepository placeRepository;
    private final RecommendationService recommendationService;
    private final AiConversationRepository conversationRepository;
    private final AiMessageRepository messageRepository;
    private final AtomicInteger currentKeyIndex = new AtomicInteger(0);

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        return new RestTemplate(factory);
    }

    private String resolveApiUrl() {
        if (apiUrlBase != null && !apiUrlBase.isBlank() && !apiUrlBase.contains("[")) {
            return apiUrlBase.trim();
        }
        return DEFAULT_GEMINI_URL;
    }

    private String getNextApiKey() {
        List<String> validKeys = new ArrayList<>();
        for (String k : Arrays.asList(primaryKey, fallbackKey1, fallbackKey2, fallbackKey3)) {
            if (k != null && !k.isBlank()) {
                validKeys.add(k.trim());
            }
        }
        if (validKeys.isEmpty()) {
            throw new IllegalStateException("Chưa cấu hình Gemini API Key trong application.properties");
        }
        int index = currentKeyIndex.getAndUpdate(i -> (i + 1) % validKeys.size());
        return validKeys.get(index);
    }

    @Transactional(readOnly = true)
    public Trip getAuthorizedTrip(Long tripId, User currentUser) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến đi với ID: " + tripId));

        boolean isOwner = trip.getUser() != null && Objects.equals(trip.getUser().getId(), currentUser.getId());
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
        for (User u : userRepository.findAll()) {
            if (usernameOrEmail.equalsIgnoreCase(u.getEmail()) || usernameOrEmail.equalsIgnoreCase(u.getFullName())) {
                return u;
            }
        }
        throw new AccessDeniedException("Không tìm thấy thông tin người dùng.");
    }

    public TripContextDTO buildCanonicalTripContext(Trip trip) {
        List<TripContextDTO.TripDaySummaryDTO> daySummaries = new ArrayList<>();
        if (trip.getTripDays() != null) {
            List<TripDay> sortedDays = new ArrayList<>(trip.getTripDays());
            sortedDays.sort(Comparator.comparing(TripDay::getDayNumber, Comparator.nullsLast(Comparator.naturalOrder())));

            for (TripDay day : sortedDays) {
                List<String> existingPlaces = new ArrayList<>();
                if (day.getDestinations() != null) {
                    for (TripDestination dest : day.getDestinations()) {
                        if (dest != null && dest.getPlaceName() != null) {
                            existingPlaces.add(dest.getPlaceName());
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

        Optional<AiConversation> convOpt = conversationRepository.findByTripIdAndUserId(trip.getId(), currentUser.getId());
        if (convOpt.isEmpty()) {
            return AiChatResponse.builder()
                    .conversationId(null)
                    .phase("ACTIVE")
                    .history(Collections.emptyList())
                    .build();
        }

        AiConversation conv = convOpt.get();
        List<AiMessage> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.getId());
        List<AiChatResponse.MessageHistoryDTO> historyDTOs = new ArrayList<>();

        for (AiMessage m : messages) {
            if ("USER".equalsIgnoreCase(m.getSenderType()) || "AI".equalsIgnoreCase(m.getSenderType())) {
                historyDTOs.add(AiChatResponse.MessageHistoryDTO.builder()
                        .id(m.getId())
                        .sender("USER".equalsIgnoreCase(m.getSenderType()) ? "user" : "ai")
                        .text(m.getContent())
                        .recommendations(restoreRecommendationsFromActionData(m.getActionData()))
                        .build());
            }
        }

        return AiChatResponse.builder()
                .conversationId(conv.getId())
                .phase(conv.getStatus())
                .history(historyDTOs)
                .build();
    }

    @Transactional
    public AiChatResponse processChat(AiChatRequest request) {
        User currentUser = getCurrentAuthenticatedUser();
        Trip trip = getAuthorizedTrip(request.getTripId(), currentUser);
        TripContextDTO canonicalContext = buildCanonicalTripContext(trip);

        AiConversation conversation = conversationRepository
                .findByTripIdAndUserId(trip.getId(), currentUser.getId())
                .orElseGet(() -> conversationRepository.save(AiConversation.builder()
                        .tripId(trip.getId())
                        .userId(currentUser.getId())
                        .status("ACTIVE")
                        .build()));

        messageRepository.save(AiMessage.builder()
                .conversationId(conversation.getId())
                .senderType("USER")
                .content(request.getUserMessage())
                .build());

        List<AiMessage> recentMessages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        List conversationHistory = new ArrayList();
        int startIdx = Math.max(0, recentMessages.size() - 10);
        for (int i = startIdx; i < recentMessages.size(); i++) {
            AiMessage msg = recentMessages.get(i);
            if (msg.getContent() != null && !msg.getContent().isBlank()) {
                String geminiRole = "USER".equalsIgnoreCase(msg.getSenderType()) ? "user" : "model";
                conversationHistory.add(Map.of(
                        "role", geminiRole,
                        "parts", List.of(Map.of("text", msg.getContent()))
                ));
            }
        }

        List tools = buildTravelTools();
        String systemPrompt = buildSystemInstruction(canonicalContext);

        int maxToolCalls = 5;
        int currentCall = 0;
        List<PlaceDiscoveryDTO> collectedRecommendations = new ArrayList<>();
        String finalAssistantText = null;

        RestTemplate restTemplate = createRestTemplate();

        while (currentCall < maxToolCalls) {
            Map requestBody = new HashMap();
            requestBody.put("system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
            requestBody.put("contents", conversationHistory);
            requestBody.put("tools", tools);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            try {
                String apiUrl = resolveApiUrl() + "?key=" + getNextApiKey();
                ResponseEntity responseEntity = restTemplate.postForEntity(
                        apiUrl, new HttpEntity(requestBody, headers), Map.class);
                Map response = (Map) responseEntity.getBody();

                if (response == null || !(response.get("candidates") instanceof List candidates) || candidates.isEmpty()) {
                    throw new RuntimeException("Phản hồi từ Gemini không có candidates hợp lệ.");
                }

                Map firstCandidate = (Map) candidates.get(0);
                Map content = (Map) firstCandidate.get("content");
                List parts = (content != null && content.get("parts") instanceof List)
                        ? (List) content.get("parts")
                        : Collections.emptyList();

                boolean hasFunctionCall = false;
                List functionResponseParts = new ArrayList();

                if (content != null) {
                    conversationHistory.add(content);
                }

                for (Object partObj : parts) {
                    Map part = (Map) partObj;
                    if (part.containsKey("functionCall")) {
                        hasFunctionCall = true;
                        Map functionCall = (Map) part.get("functionCall");
                        String functionName = String.valueOf(functionCall.get("name"));
                        Map args = (Map) functionCall.get("args");

                        Object toolResult = toolExecutor.executeTool(functionName, args, canonicalContext);
                        if (toolResult instanceof List listResult) {
                            for (Object item : listResult) {
                                if (item instanceof PlaceDiscoveryDTO dto) {
                                    collectedRecommendations.add(dto);
                                }
                            }
                        }

                        functionResponseParts.add(Map.of(
                                "functionResponse", Map.of(
                                        "name", functionName,
                                        "response", Map.of(
                                                "destination", canonicalContext.getDestination(),
                                                "result", toolResult
                                        )
                                )
                        ));
                    }

                    if (part.containsKey("text") && part.get("text") != null) {
                        finalAssistantText = String.valueOf(part.get("text"));
                    }
                }

                if (!hasFunctionCall) {
                    break;
                }

                conversationHistory.add(Map.of("role", "function", "parts", functionResponseParts));
                currentCall++;

            } catch (HttpStatusCodeException httpEx) {
                log.error("Lỗi HTTP từ Gemini API (Status {}): {}", httpEx.getStatusCode().value(), httpEx.getMessage());
                if (httpEx.getStatusCode().value() == 429) {
                    finalAssistantText = "Trợ lý AI hiện đang xử lý nhiều yêu cầu. Dưới đây là các địa điểm thực tế tại "
                            + canonicalContext.getDestination() + " được hệ thống tìm thấy cho bạn:";
                    if (collectedRecommendations.isEmpty()) {
                        Object fallbackRes = toolExecutor.executeTool("search_places", Map.of("limit", 5), canonicalContext);
                        if (fallbackRes instanceof List listResult) {
                            for (Object item : listResult) {
                                if (item instanceof PlaceDiscoveryDTO dto) {
                                    collectedRecommendations.add(dto);
                                }
                            }
                        }
                    }
                } else {
                    finalAssistantText = "Không thể kết nối tới dịch vụ AI lúc này. Vui lòng thử lại sau ít phút.";
                }
                break;
            } catch (Exception e) {
                log.error("Lỗi xử lý AI Chat cho tripId={}: {}", trip.getId(), e.getMessage());
                finalAssistantText = "Đã xảy ra lỗi khi xử lý yêu cầu. Vui lòng thử lại.";
                break;
            }
        }

        if (finalAssistantText == null || finalAssistantText.isBlank()) {
            finalAssistantText = "Mình đã tìm thấy các địa điểm thực tế tại " + canonicalContext.getDestination() + " phù hợp với chuyến đi của bạn.";
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

    private String serializePlaceIdsToJson(List<PlaceDiscoveryDTO> recs) {
        if (recs == null || recs.isEmpty()) return null;
        List<String> idStrings = new ArrayList<>();
        for (PlaceDiscoveryDTO dto : recs) {
            if (dto != null && dto.getPlaceId() != null) {
                idStrings.add(String.valueOf(dto.getPlaceId()));
            }
        }
        return idStrings.isEmpty() ? null : "[" + String.join(",", idStrings) + "]";
    }

    private List<PlaceDiscoveryDTO> restoreRecommendationsFromActionData(String actionData) {
        if (actionData == null || actionData.isBlank()) return Collections.emptyList();
        String cleaned = actionData.replace("[", "").replace("]", "").trim();
        if (cleaned.isEmpty()) return Collections.emptyList();

        List<Long> ids = new ArrayList<>();
        for (String part : cleaned.split(",")) {
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {}
        }
        if (ids.isEmpty()) return Collections.emptyList();

        List<Place> foundPlaces = placeRepository.findAllById(ids);
        List<PlaceDiscoveryDTO> result = new ArrayList<>();
        for (Place place : foundPlaces) {
            PlaceDiscoveryDTO dto = recommendationService.toDiscoveryDTO(place);
            if (dto != null) {
                result.add(dto);
            }
        }
        return result;
    }

    private String buildSystemInstruction(TripContextDTO ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bạn là Trợ lý Lập kế hoạch Du lịch AI thông minh của TravelPlatform, giao tiếp bằng tiếng Việt tự nhiên, súc tích.\n");
        sb.append("=== THÔNG TIN CHUYẾN ĐI HIỆN TẠI (NGUỒN SỰ THẬT DUY NHẤT) ===\n");
        sb.append("- Điểm đến (Destination): ").append(ctx.getDestination() != null ? ctx.getDestination() : "Chưa xác định").append("\n");
        sb.append("- Tên chuyến đi: ").append(ctx.getTitle() != null ? ctx.getTitle() : "").append("\n");
        sb.append("- Ngày bắt đầu: ").append(ctx.getStartDate() != null ? ctx.getStartDate() : "Chưa đặt").append("\n");
        sb.append("- Ngày kết thúc: ").append(ctx.getEndDate() != null ? ctx.getEndDate() : "Chưa đặt").append("\n");
        sb.append("- Số thành viên: ").append(ctx.getMemberCount() != null ? ctx.getMemberCount() : 1).append(" người\n");
        sb.append("- Tổng ngân sách: ").append(ctx.getTotalBudget() != null ? ctx.getTotalBudget().toPlainString() + " VNĐ" : "Chưa đặt").append("\n");

        if (ctx.getTripDays() != null && !ctx.getTripDays().isEmpty()) {
            sb.append("- Các ngày trong lịch trình:\n");
            for (TripContextDTO.TripDaySummaryDTO d : ctx.getTripDays()) {
                sb.append("  + Ngày ").append(d.getDayNumber()).append(" (").append(d.getDayDate()).append("): ")
                        .append(d.getExistingPlaces().isEmpty() ? "Chưa có địa điểm" : String.join(", ", d.getExistingPlaces()))
                        .append("\n");
            }
        }

        sb.append("\n=== QUY TẮC BẮT BUỘC ===\n");
        sb.append("1. Điểm đến đã là '").append(ctx.getDestination()).append("'. TUYỆT ĐỐI KHÔNG BAO GIỜ hỏi lại 'Bạn muốn đi du lịch ở đâu?'.\n");
        sb.append("2. Không hỏi lại ngày đi, ngày về, ngân sách hay số người nếu đã có ở trên.\n");
        sb.append("3. Khi người dùng nhờ gợi ý địa điểm tham quan, quán ăn, cà phê hoặc khách sạn, BẮT BUỘC gọi công cụ (search_places, search_restaurants, search_accommodations).\n");
        sb.append("4. Chỉ sử dụng địa điểm thực tế từ kết quả công cụ trả về. TUYỆT ĐỐI KHÔNG tự bịa tên địa điểm, địa chỉ, rating hay giá tiền.\n");
        sb.append("5. Nếu dữ liệu trả về có rating=null hoặc estimatedPrice=null, hãy nói rõ là dữ liệu từ OpenStreetMap hiện chưa có thông tin giá/đánh giá, không tự điền 0đ.\n");
        sb.append("6. Hướng dẫn người dùng chọn Ngày trên thẻ gợi ý bên dưới và bấm 'Thêm vào ngày này' để xác nhận đưa vào lịch trình.\n");
        return sb.toString();
    }

    private List buildTravelTools() {
        Map searchPlaces = new HashMap();
        searchPlaces.put("name", "search_places");
        searchPlaces.put("description", "Tìm địa điểm tham quan, du lịch, check-in thực tế tại điểm đến của chuyến đi từ OpenStreetMap");
        searchPlaces.put("parameters", Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "category", Map.of("type", "STRING", "description", "Loại địa điểm: ATTRACTION, RESTAURANT, CAFE, ACCOMMODATION"),
                        "limit", Map.of("type", "INTEGER", "description", "Số lượng kết quả tối đa (1 đến 8)")
                )
        ));

        Map searchRestaurants = new HashMap();
        searchRestaurants.put("name", "search_restaurants");
        searchRestaurants.put("description", "Tìm nhà hàng, quán ăn hoặc quán cà phê thực tế tại điểm đến của chuyến đi");
        searchRestaurants.put("parameters", Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "type", Map.of("type", "STRING", "description", "RESTAURANT hoặc CAFE"),
                        "limit", Map.of("type", "INTEGER", "description", "Số lượng kết quả (mặc định 5)")
                )
        ));

        Map searchAccommodations = new HashMap();
        searchAccommodations.put("name", "search_accommodations");
        searchAccommodations.put("description", "Tìm khách sạn, homestay, nơi lưu trú thực tế tại điểm đến của chuyến đi");
        searchAccommodations.put("parameters", Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "limit", Map.of("type", "INTEGER", "description", "Số lượng kết quả (mặc định 5)")
                )
        ));

        Map calculateBudget = new HashMap();
        calculateBudget.put("name", "calculate_budget");
        calculateBudget.put("description", "Tính toán phân bổ ngân sách gợi ý theo ngày và theo đầu người dựa trên tổng ngân sách chuyến đi");
        calculateBudget.put("parameters", Map.of(
                "type", "OBJECT",
                "properties", Collections.emptyMap()
        ));

        return List.of(Map.of("function_declarations", List.of(
                searchPlaces, searchRestaurants, searchAccommodations, calculateBudget
        )));
    }
}