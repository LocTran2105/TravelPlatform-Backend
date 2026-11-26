package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.AiChatRequest;
import hcmute.edu.vn.backend.dto.AiChatResponse;
import hcmute.edu.vn.backend.dto.PlaceDiscoveryDTO;
import hcmute.edu.vn.backend.dto.TripContextDTO;
import hcmute.edu.vn.backend.entity.AiConversation;
import hcmute.edu.vn.backend.entity.AiMessage;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.AiConversationRepository;
import hcmute.edu.vn.backend.repository.AiMessageRepository;
import hcmute.edu.vn.backend.repository.PlaceRepository;
import hcmute.edu.vn.backend.repository.PlaceReviewRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trợ lý AI của chuyến đi.
 *
 * Luồng xử lý (tối ưu cho Gemini gói miễn phí: tối đa 1 lần gọi AI cho mỗi tin nhắn):
 *   1. CODE - nạp chuyến đi (kiểm tra quyền thành viên), nhận diện yêu cầu tìm địa điểm và số lượng.
 *   2. CODE - lấy địa điểm từ DB/OSM: Partner đã xác minh trước, rồi POI OSM; xếp hạng thêm theo
 *             đánh giá cộng đồng.
 *   3. AI   - MỘT lần gọi (JSON): kiểm tra yêu cầu có hợp lệ không, viết lời giới thiệu, lý do cho từng
 *             địa điểm và, CHỈ KHI DB thiếu, đề xuất thêm tên địa điểm để bù.
 *   4. CODE - đối chiếu tên AI đề xuất trên bản đồ (Photon/OSM). Tìm thấy -> địa điểm thật, kéo thả được.
 *             Không thấy -> nhãn "Tham khảo", không có tọa độ, KHÔNG lưu vào bảng places.
 *   5. CODE - gắn nhãn độ tin cậy và kiểm tra số lượng so với yêu cầu, rồi trả về.
 * Nhãn độ tin cậy luôn do backend gắn, AI không thể tự nhận địa điểm là "đã xác minh".
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({"unchecked", "rawtypes"})
public class GeminiAiService {

    public static final String TRUST_VERIFIED = "VERIFIED";
    public static final String TRUST_MAP_DATA = "MAP_DATA";
    public static final String TRUST_AI_REFERENCE = "AI_REFERENCE";

    private static final String LABEL_VERIFIED = "Đã xác minh";
    private static final String LABEL_MAP_DATA = "Có trên bản đồ";
    private static final String LABEL_MAP_DATA_AI = "Có trên bản đồ · AI gợi ý";
    private static final String LABEL_REFERENCE = "Tham khảo · chưa xác minh vị trí";

    private static final int DEFAULT_PLACE_COUNT = 5;
    private static final int MAX_PLACE_COUNT = 8;
    private static final int MAX_USER_MESSAGE_LENGTH = 1_000;
    private static final int HISTORY_MESSAGES_FOR_AI = 6;
    private static final int MAX_HISTORY_TEXT = 600;

    // Xếp hạng theo đánh giá cộng đồng (trung bình Bayes): địa điểm ít đánh giá không vượt mặt
    // địa điểm nhiều đánh giá chỉ nhờ một lượt 5 sao.
    private static final double RATING_PRIOR_MEAN = 3.5;
    private static final double RATING_PRIOR_WEIGHT = 3.0;

    private static final Pattern COUNT_DIGITS = Pattern.compile(
            "\\b(\\d{1,2})\\s*(dia diem|diem|quan|nha hang|khach san|homestay|cho|noi|canh|resort|hostel)");
    private static final Pattern COUNT_WORDS = Pattern.compile(
            "\\b(mot|hai|ba|bon|nam|sau|bay|tam)\\s+(dia diem|diem|quan|nha hang|khach san|homestay|cho|noi|canh)");
    private static final Pattern DAY_PATTERN = Pattern.compile("\\bngay\\s*(?:thu\\s*)?(\\d{1,2})\\b");
    private static final Map<String, Integer> NUMBER_WORDS = Map.of(
            "mot", 1, "hai", 2, "ba", 3, "bon", 4, "nam", 5, "sau", 6, "bay", 7, "tam", 8);

    private final AiToolExecutor toolExecutor;
    private final GeminiClient geminiClient;
    private final UserRepository userRepository;
    private final PlaceRepository placeRepository;
    private final PlaceReviewRepository placeReviewRepository;
    private final RecommendationService recommendationService;
    private final TripContextService tripContextService;
    private final TripAccessService tripAccessService;
    private final AiConversationRepository conversationRepository;
    private final AiMessageRepository messageRepository;
    private final JsonMapper jsonMapper;
    private final AiRateLimiter aiRateLimiter;

    // ======================================================================
    // Người dùng hiện tại & lịch sử
    // ======================================================================

    public User getCurrentAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new AccessDeniedException("Vui lòng đăng nhập để sử dụng trợ lý AI.");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Không tìm thấy thông tin người dùng."));
    }

    @Transactional(readOnly = true)
    public AiChatResponse getConversationHistory(Long tripId) {
        User currentUser = getCurrentAuthenticatedUser();
        // Chỉ chủ chuyến đi hoặc tripmate mới dùng trợ lý AI của chuyến đi.
        tripAccessService.requireMember(tripId, currentUser.getEmail());

        Optional<AiConversation> convOpt = conversationRepository
                .findByTripIdAndUserId(tripId, currentUser.getId());
        if (convOpt.isEmpty()) {
            return AiChatResponse.builder()
                    .conversationId(null)
                    .phase("ACTIVE")
                    .history(Collections.emptyList())
                    .build();
        }

        AiConversation conversation = convOpt.get();
        List<AiChatResponse.MessageHistoryDTO> historyDTOs = new ArrayList<>();
        for (AiMessage message : messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId())) {
            if ("USER".equalsIgnoreCase(message.getSenderType()) || "AI".equalsIgnoreCase(message.getSenderType())) {
                historyDTOs.add(AiChatResponse.MessageHistoryDTO.builder()
                        .id(message.getId())
                        .sender("USER".equalsIgnoreCase(message.getSenderType()) ? "user" : "ai")
                        .text(message.getContent())
                        .recommendations(restoreRecommendations(message.getActionData()))
                        .build());
            }
        }

        return AiChatResponse.builder()
                .conversationId(conversation.getId())
                .phase(conversation.getStatus())
                .history(historyDTOs)
                .build();
    }

    // ======================================================================
    // Xử lý tin nhắn
    // ======================================================================

    public AiChatResponse processChat(AiChatRequest request) {
        if (request == null || request.getTripId() == null) {
            throw new IllegalArgumentException("Thiếu tripId trong yêu cầu AI Chat.");
        }
        if (request.getUserMessage() == null || request.getUserMessage().isBlank()) {
            throw new IllegalArgumentException("Tin nhắn không được để trống.");
        }
        String userMessage = limit(request.getUserMessage().trim(), MAX_USER_MESSAGE_LENGTH);

        User currentUser = getCurrentAuthenticatedUser();
        TripContextDTO context = tripContextService.loadContextForMember(request.getTripId(), currentUser.getEmail());
        Long tripId = context.getTripId();

        AiConversation conversation = conversationRepository
                .findByTripIdAndUserId(tripId, currentUser.getId())
                .orElseGet(() -> conversationRepository.save(AiConversation.builder()
                        .tripId(tripId)
                        .userId(currentUser.getId())
                        .status("ACTIVE")
                        .build()));

        // Lịch sử gửi cho AI: vài tin gần nhất TRƯỚC tin hiện tại, cắt ngắn để tiết kiệm token.
        List<Map> history = buildRecentHistory(conversation.getId());

        messageRepository.save(AiMessage.builder()
                .conversationId(conversation.getId())
                .senderType("USER")
                .content(userMessage)
                .build());

        // Giới hạn lượt gọi AI theo người dùng để một tài khoản không dùng hết hạn mức miễn phí của cả hệ thống.
        boolean aiAllowed = aiRateLimiter.tryAcquire(currentUser.getId());
        // "Ngày 2 nên đi đâu, ưu tiên gần An Cafe": xác định ngày đích + địa điểm mốc trong lịch trình.
        PlanFocus focus = resolvePlanFocus(userMessage, context);
        ChatOutcome outcome = isPlaceDiscoveryIntent(focus.intentText())
                ? handlePlaceDiscovery(userMessage, withFocus(context, focus), history, aiAllowed, focus)
                : handleGeneralChat(userMessage, withFocus(context, focus), history, aiAllowed);

        messageRepository.save(AiMessage.builder()
                .conversationId(conversation.getId())
                .senderType("AI")
                .content(outcome.text())
                .actionData(serializeRecommendations(outcome.recommendations()))
                .build());

        return AiChatResponse.builder()
                .conversationId(conversation.getId())
                .assistantMessage(outcome.text())
                .phase(conversation.getStatus())
                .recommendations(outcome.recommendations())
                .requiresConfirmation(!outcome.recommendations().isEmpty())
                .targetDayId(focus.dayId())
                .targetDayNumber(focus.dayNumber())
                .anchorName(outcome.recommendations().isEmpty() ? null : focus.anchorName())
                .build();
    }

    private record ChatOutcome(String text, List<PlaceDiscoveryDTO> recommendations) { }

    /**
     * Trọng tâm của câu hỏi: ngày đích (nếu nhắc "ngày N"), địa điểm mốc để ưu tiên ở gần,
     * và câu đã bỏ tên các địa điểm trong lịch trình (để "An Cafe" không làm câu hỏi bị hiểu là tìm quán cà phê).
     */
    private record PlanFocus(Long dayId, Integer dayNumber, String anchorName, Double anchorLatitude,
                             Double anchorLongitude, boolean anchorMentioned, String intentText) { }

    private PlanFocus resolvePlanFocus(String message, TripContextDTO context) {
        String normalized = normalizeName(message);
        List<TripContextDTO.TripDaySummaryDTO> days = context.getTripDays() == null ? List.of() : context.getTripDays();

        // 1. Ngày đích.
        TripContextDTO.TripDaySummaryDTO targetDay = null;
        Matcher dayMatcher = DAY_PATTERN.matcher(normalizeVietnamese(message));
        if (dayMatcher.find()) {
            int dayNumber = Integer.parseInt(dayMatcher.group(1));
            targetDay = days.stream().filter(d -> Objects.equals(d.getDayNumber(), dayNumber)).findFirst().orElse(null);
        }

        // 2. Địa điểm trong lịch trình được nhắc tên (chọn tên dài nhất khớp).
        TripContextDTO.PlacePoint mentioned = null;
        String intentText = " " + normalized + " ";
        for (TripContextDTO.TripDaySummaryDTO day : days) {
            if (day.getPlacePoints() == null) continue;
            for (TripContextDTO.PlacePoint point : day.getPlacePoints()) {
                String name = normalizeName(point.getName());
                if (name.length() < 4 || !intentText.contains(" " + name + " ")) continue;
                if (mentioned == null || name.length() > normalizeName(mentioned.getName()).length()) mentioned = point;
            }
        }
        // Bỏ tên mọi địa điểm đã có trong lịch trình khỏi câu dùng để nhận diện nhu cầu.
        for (TripContextDTO.TripDaySummaryDTO day : days) {
            if (day.getExistingPlaces() == null) continue;
            for (String existing : day.getExistingPlaces()) {
                String name = normalizeName(existing);
                if (name.length() >= 4) intentText = intentText.replace(" " + name + " ", " ");
            }
        }

        // 3. Mốc: địa điểm được nhắc > điểm đầu của ngày đích > điểm cuối của ngày gần nhất trước đó.
        TripContextDTO.PlacePoint anchor = mentioned;
        if (anchor == null && targetDay != null) {
            if (targetDay.getPlacePoints() != null && !targetDay.getPlacePoints().isEmpty()) {
                anchor = targetDay.getPlacePoints().get(0);
            } else {
                for (int i = days.indexOf(targetDay) - 1; i >= 0 && anchor == null; i--) {
                    List<TripContextDTO.PlacePoint> points = days.get(i).getPlacePoints();
                    if (points != null && !points.isEmpty()) anchor = points.get(points.size() - 1);
                }
            }
        }

        return new PlanFocus(
                targetDay != null ? targetDay.getDayId() : null,
                targetDay != null ? targetDay.getDayNumber() : null,
                anchor != null ? anchor.getName() : context.getAnchorName(),
                anchor != null ? anchor.getLatitude() : context.getAnchorLatitude(),
                anchor != null ? anchor.getLongitude() : context.getAnchorLongitude(),
                mentioned != null,
                intentText.trim());
    }

    /** Bản sao ngữ cảnh với điểm mốc theo trọng tâm câu hỏi (không sửa ngữ cảnh gốc). */
    private static TripContextDTO withFocus(TripContextDTO context, PlanFocus focus) {
        return TripContextDTO.builder()
                .tripId(context.getTripId())
                .title(context.getTitle())
                .destination(context.getDestination())
                .startDate(context.getStartDate())
                .endDate(context.getEndDate())
                .memberCount(context.getMemberCount())
                .totalBudget(context.getTotalBudget())
                .tripDays(context.getTripDays())
                .anchorName(focus.anchorName())
                .anchorLatitude(focus.anchorLatitude())
                .anchorLongitude(focus.anchorLongitude())
                .build();
    }

    // ----------------------------------------------------------------------
    // Gợi ý địa điểm
    // ----------------------------------------------------------------------

    private ChatOutcome handlePlaceDiscovery(String userMessage, TripContextDTO context, List<Map> history,
                                             boolean aiAllowed, PlanFocus focus) {
        String destination = safeDestination(context);
        if (context.getDestination() == null || context.getDestination().isBlank()) {
            return new ChatOutcome("Chuyến đi chưa có điểm đến nên mình chưa tìm được địa điểm. "
                    + "Bạn hãy cập nhật điểm đến cho chuyến đi trước nhé.", List.of());
        }

        int requested = parseRequestedCount(focus.intentText());
        String primaryCategory = detectPrimaryCategory(focus.intentText());

        // Bước 2: dữ liệu thật trong hệ thống (Partner đã duyệt + OSM).
        List<PlaceDiscoveryDTO> fromSystem = new ArrayList<>();
        try {
            collectIntentBasedRecommendations(focus.intentText(), context, fromSystem);
        } catch (Exception ex) {
            log.warn("Lấy địa điểm từ DB/OSM thất bại cho tripId={}: {}", context.getTripId(), ex.getMessage());
        }
        fromSystem.removeIf(place -> isAlreadyInTrip(place, context));
        fromSystem.forEach(this::applyTrustLevel);
        applyCommunityRatings(fromSystem);
        rankPlaces(fromSystem, context);
        List<PlaceDiscoveryDTO> picked = new ArrayList<>(fromSystem.subList(0, Math.min(requested, fromSystem.size())));
        int shortage = requested - picked.size();

        // Bước 3: MỘT lần gọi AI.
        DiscoveryAiAnswer ai = aiAllowed
                ? askAiForDiscovery(userMessage, context, history, picked, shortage, primaryCategory, focus)
                : null;

        if (ai != null && !ai.relevant()) {
            String text = ai.reply() != null
                    ? ai.reply()
                    : "Mình chỉ hỗ trợ các yêu cầu liên quan đến chuyến đi và địa điểm du lịch.";
            return new ChatOutcome(text, List.of());
        }

        if (ai != null) {
            for (Map.Entry<Integer, String> reason : ai.reasons().entrySet()) {
                int index = reason.getKey();
                if (index >= 0 && index < picked.size() && reason.getValue() != null) {
                    picked.get(index).setMatchReason(limit(reason.getValue(), 300));
                }
            }
        }

        // Bước 4: đối chiếu tên AI đề xuất trên bản đồ.
        if (ai != null && shortage > 0) {
            List<PlaceDiscoveryDTO> extras = verifyAiCandidates(ai.extras(), context, picked, shortage, primaryCategory);
            extras.forEach(place -> applyTravelSignals(place, context));
            picked.addAll(extras);
        }

        // Bước 5: lời nhắn + kiểm tra số lượng (phần nhãn do code viết nên luôn khớp thực tế).
        StringBuilder text = new StringBuilder();
        if (ai != null && ai.reply() != null) {
            text.append(ai.reply());
        } else if (!picked.isEmpty()) {
            text.append("Mình tìm được ").append(picked.size()).append(" địa điểm tại ").append(destination)
                    .append(" cho bạn tham khảo.");
        } else {
            text.append("Mình chưa tìm được địa điểm phù hợp tại ").append(destination).append(".");
        }

        if (!aiAllowed) {
            text.append("\n\nBạn đã dùng hết lượt hỏi AI trong ít phút vừa qua (")
                    .append(aiRateLimiter.describeLimit())
                    .append("), nên mình chỉ hiển thị dữ liệu đã có trong hệ thống.");
        } else if (ai == null && shortage > 0) {
            text.append("\n\nTrợ lý AI đang quá tải nên mình chỉ hiển thị dữ liệu đã có trong hệ thống, ")
                    .append("chưa bổ sung được gợi ý tham khảo.");
        }
        if (!picked.isEmpty()) {
            text.append("\n\n").append(buildTrustSummary(picked));
            if (context.getAnchorName() != null && (focus.anchorMentioned() || focus.dayNumber() != null)) {
                text.append("\nĐã ưu tiên các địa điểm gần ").append(context.getAnchorName()).append(".");
            }
            if (focus.dayNumber() != null) {
                text.append("\nBấm “Thêm” để đưa vào Ngày ").append(focus.dayNumber()).append(" (đã chọn sẵn).");
            }
        }
        if (picked.size() < requested) {
            text.append("\nMình chỉ tìm được ").append(picked.size()).append("/").append(requested)
                    .append(" địa điểm phù hợp với yêu cầu.");
        }
        return new ChatOutcome(text.toString(), picked);
    }

    private record AiCandidate(String name, String area, String category, String reason) { }

    private record DiscoveryAiAnswer(boolean relevant, String reply, Map<Integer, String> reasons,
                                     List<AiCandidate> extras) { }

    private DiscoveryAiAnswer askAiForDiscovery(String userMessage, TripContextDTO context, List<Map> history,
                                                List<PlaceDiscoveryDTO> picked, int shortage, String category,
                                                PlanFocus focus) {
        if (!geminiClient.hasAnyKey()) {
            return null;
        }

        StringBuilder system = new StringBuilder(buildTripSummary(context));
        system.append("\n=== NHIỆM VỤ ===\n")
                .append("Bạn là trợ lý du lịch của TravelPlatform, trả lời bằng tiếng Việt, thân thiện, ngắn gọn.\n")
                .append("1. relevant = false nếu yêu cầu không liên quan du lịch/địa điểm hoặc vi phạm pháp luật; ")
                .append("khi đó reply là lời từ chối lịch sự.\n")
                .append("2. reply: 2–4 câu giới thiệu chung về danh sách gợi ý. KHÔNG liệt kê lại từng địa điểm, ")
                .append("KHÔNG nói địa điểm nào đã được xác minh (hệ thống tự gắn nhãn).\n")
                .append("3. reasons: với từng địa điểm trong DANH SÁCH HỆ THỐNG, viết 1 câu lý do phù hợp với yêu cầu ")
                .append("(index theo danh sách).\n")
                .append("4. extras: ");
        if (shortage > 0) {
            system.append("đề xuất tối đa ").append(shortage)
                    .append(" địa điểm CÓ THẬT, nổi tiếng tại ").append(safeDestination(context))
                    .append(" mà danh sách hệ thống chưa có. Chỉ ghi tên riêng chính xác (không thêm mô tả vào tên), ")
                    .append("khu vực/phường nếu chắc chắn, và lý do. Không chắc tên có thật thì KHÔNG đưa vào. ")
                    .append("Không bịa địa chỉ chi tiết, giá, giờ mở cửa hay số sao. ")
                    .append("category là một trong: TOURIST_ATTRACTION, RESTAURANT, CAFE, ACCOMMODATION.\n");
        } else {
            system.append("để mảng rỗng (hệ thống đã đủ địa điểm).\n");
        }

        system.append("\n=== DANH SÁCH HỆ THỐNG (").append(picked.size()).append(" địa điểm) ===\n");
        for (int i = 0; i < picked.size(); i++) {
            PlaceDiscoveryDTO place = picked.get(i);
            system.append(i).append(". ").append(sanitizePromptValue(place.getName()));
            if (place.getCategory() != null) system.append(" | loại: ").append(place.getCategory());
            if (place.getAddress() != null) {
                system.append(" | ").append(limit(sanitizePromptValue(place.getAddress()), 120));
            }
            if (place.getRating() != null) {
                system.append(" | ").append(place.getRating().setScale(1, RoundingMode.HALF_UP)).append(" sao");
                if (place.getUserRatingsTotal() != null) {
                    system.append(" (").append(place.getUserRatingsTotal()).append(" đánh giá)");
                }
            }
            system.append("\n");
        }
        system.append("Loại địa điểm người dùng quan tâm: ").append(category).append("\n");
        if (focus.dayNumber() != null) {
            system.append("Người dùng đang lên kế hoạch cho Ngày ").append(focus.dayNumber()).append(".\n");
        }
        if (context.getAnchorName() != null) {
            system.append(focus.anchorMentioned() ? "Người dùng muốn ưu tiên gần: " : "Điểm mốc của lộ trình: ")
                    .append(sanitizePromptValue(context.getAnchorName()))
                    .append(". Ưu tiên địa điểm thuận đường, gần đó khi đề xuất extras; ")
                    .append("không đề xuất lại địa điểm đã có trong lịch trình.\n");
        }

        Map schema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "relevant", Map.of("type", "BOOLEAN"),
                        "reply", Map.of("type", "STRING"),
                        "reasons", Map.of("type", "ARRAY", "items", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "index", Map.of("type", "INTEGER"),
                                        "reason", Map.of("type", "STRING")),
                                "required", List.of("index", "reason"))),
                        "extras", Map.of("type", "ARRAY", "items", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "name", Map.of("type", "STRING"),
                                        "area", Map.of("type", "STRING"),
                                        "category", Map.of("type", "STRING"),
                                        "reason", Map.of("type", "STRING")),
                                "required", List.of("name", "reason")))
                ),
                "required", List.of("relevant", "reply", "reasons", "extras")
        );

        List<Map> contents = new ArrayList<>(history);
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", userMessage))));

        GeminiClient.GeminiResult result = geminiClient.generate(system.toString(), contents, schema, 1_500);
        if (!result.ok()) {
            log.warn("Gemini (gợi ý địa điểm) thất bại: {}", result.errorReason());
            return null;
        }

        try {
            JsonNode root = jsonMapper.readTree(result.text());
            boolean relevant = root.get("relevant") == null || root.get("relevant").asBoolean(true);
            String reply = limit(textOf(root, "reply"), 1_200);

            Map<Integer, String> reasons = new HashMap<>();
            JsonNode reasonNodes = root.get("reasons");
            if (reasonNodes != null && reasonNodes.isArray()) {
                for (JsonNode node : reasonNodes) {
                    if (node.get("index") != null) reasons.put(node.get("index").asInt(-1), textOf(node, "reason"));
                }
            }

            List<AiCandidate> extras = new ArrayList<>();
            JsonNode extraNodes = root.get("extras");
            if (shortage > 0 && extraNodes != null && extraNodes.isArray()) {
                for (JsonNode node : extraNodes) {
                    String name = limit(textOf(node, "name"), 100);
                    if (name == null) continue;
                    extras.add(new AiCandidate(name, limit(textOf(node, "area"), 150),
                            textOf(node, "category"), limit(textOf(node, "reason"), 300)));
                    if (extras.size() >= shortage) break;
                }
            }
            return new DiscoveryAiAnswer(relevant, reply, reasons, extras);
        } catch (Exception ex) {
            log.warn("Không đọc được JSON từ Gemini: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Đối chiếu tên AI đề xuất bằng Photon/OSM (OsmPlaceDiscoveryService chỉ nhận đúng tên, đúng loại, trong bán kính).
     * Khớp -> Place thật (đã lưu OSM, có tọa độ). Không khớp -> mục "Tham khảo" không có placeId.
     */
    private List<PlaceDiscoveryDTO> verifyAiCandidates(List<AiCandidate> candidates, TripContextDTO context,
                                                       List<PlaceDiscoveryDTO> existing, int shortage,
                                                       String fallbackCategory) {
        if (candidates == null || candidates.isEmpty() || shortage <= 0) {
            return List.of();
        }

        Set<String> existingNames = new HashSet<>();
        Set<Long> existingIds = new HashSet<>();
        for (PlaceDiscoveryDTO place : existing) {
            existingNames.add(normalizeName(place.getName()));
            if (place.getPlaceId() != null) existingIds.add(place.getPlaceId());
        }
        if (context.getTripDays() != null) {
            for (TripContextDTO.TripDaySummaryDTO day : context.getTripDays()) {
                if (day.getExistingPlaces() != null) day.getExistingPlaces().forEach(n -> existingNames.add(normalizeName(n)));
            }
        }

        List<AiCandidate> fresh = new ArrayList<>();
        for (AiCandidate candidate : candidates) {
            if (existingNames.add(normalizeName(candidate.name()))) fresh.add(candidate);
        }
        if (fresh.isEmpty()) return List.of();

        // Gom theo loại để mỗi loại chỉ gọi xác minh một lần (tối đa 2 loại mỗi tin nhắn).
        Map<String, List<AiCandidate>> byCategory = new LinkedHashMap<>();
        for (AiCandidate candidate : fresh) {
            byCategory.computeIfAbsent(normalizeCategory(candidate.category(), fallbackCategory),
                    key -> new ArrayList<>()).add(candidate);
        }

        Map<AiCandidate, PlaceDiscoveryDTO> verified = new HashMap<>();
        int categoryCalls = 0;
        for (Map.Entry<String, List<AiCandidate>> group : byCategory.entrySet()) {
            if (categoryCalls++ >= 2) break;
            List<String> names = group.getValue().stream().map(AiCandidate::name).toList();
            List<PlaceDiscoveryDTO> matches;
            try {
                matches = recommendationService.discoverNamedPlaces(
                        context.getTripId(), context.getDestination(), group.getKey(), names, names.size());
            } catch (Exception ex) {
                log.warn("Xác minh tên AI đề xuất thất bại ({}): {}", group.getKey(), ex.getMessage());
                continue;
            }
            for (PlaceDiscoveryDTO match : matches) {
                if (match == null || match.getPlaceId() == null || existingIds.contains(match.getPlaceId())) continue;
                AiCandidate owner = findCandidateFor(match, group.getValue(), verified.keySet());
                if (owner != null) {
                    verified.put(owner, match);
                    existingIds.add(match.getPlaceId());
                }
            }
        }

        List<PlaceDiscoveryDTO> result = new ArrayList<>();
        for (AiCandidate candidate : fresh) {
            if (result.size() >= shortage) break;
            PlaceDiscoveryDTO match = verified.get(candidate);
            if (match != null) {
                applyTrustLevel(match);
                match.setAiSuggested(true);
                if (TRUST_MAP_DATA.equals(match.getTrustLevel())) match.setTrustLabel(LABEL_MAP_DATA_AI);
                if (candidate.reason() != null) match.setMatchReason(candidate.reason());
                result.add(match);
            } else {
                result.add(toReference(candidate, context.getDestination(), fallbackCategory));
            }
        }
        return result;
    }

    private AiCandidate findCandidateFor(PlaceDiscoveryDTO match, List<AiCandidate> candidates, Set<AiCandidate> used) {
        String matchName = normalizeName(match.getName());
        for (AiCandidate candidate : candidates) {
            if (used.contains(candidate)) continue;
            String candidateName = normalizeName(candidate.name());
            if (!candidateName.isEmpty() && !matchName.isEmpty()
                    && (matchName.contains(candidateName) || candidateName.contains(matchName))) {
                return candidate;
            }
        }
        return null;
    }

    private PlaceDiscoveryDTO toReference(AiCandidate candidate, String destination, String fallbackCategory) {
        return buildReference(candidate.name(), candidate.area(),
                normalizeCategory(candidate.category(), fallbackCategory), candidate.reason(), destination);
    }

    private static PlaceDiscoveryDTO buildReference(String name, String area, String category,
                                                    String reason, String destination) {
        return PlaceDiscoveryDTO.builder()
                .placeId(null)
                .provider("AI")
                .name(name)
                // Khu vực do AI nêu, chỉ giúp người dùng tự tìm lại; không phải địa chỉ đã xác minh.
                .address(area)
                .destination(destination)
                .category(category)
                .matchReason(reason)
                .priceType("UNKNOWN")
                .trustLevel(TRUST_AI_REFERENCE)
                .trustLabel(LABEL_REFERENCE)
                .aiSuggested(true)
                .attribution("Gợi ý từ AI, chưa được hệ thống xác minh")
                .build();
    }

    // ----------------------------------------------------------------------
    // Trò chuyện chung (lịch trình, ngân sách, câu hỏi du lịch)
    // ----------------------------------------------------------------------

    private ChatOutcome handleGeneralChat(String userMessage, TripContextDTO context, List<Map> history,
                                          boolean aiAllowed) {
        if (!aiAllowed) {
            return new ChatOutcome("Bạn đã dùng hết lượt hỏi AI trong ít phút vừa qua ("
                    + aiRateLimiter.describeLimit() + "). Bạn thử lại sau ít phút nhé. "
                    + "Trong lúc chờ, bạn vẫn có thể gõ \"gợi ý địa điểm ...\" để xem dữ liệu đã có trong hệ thống.",
                    List.of());
        }
        StringBuilder system = new StringBuilder(buildTripSummary(context));
        system.append("\n=== QUY TẮC ===\n")
                .append("Bạn là trợ lý du lịch của TravelPlatform, trả lời bằng tiếng Việt, tự nhiên, tối đa khoảng 200 từ.\n")
                .append("- Chỉ hỗ trợ nội dung liên quan đến chuyến đi, du lịch, lịch trình, ngân sách. Yêu cầu khác: từ chối lịch sự.\n")
                .append("- Không hỏi lại điểm đến, ngày đi, số người, ngân sách nếu đã có ở trên.\n")
                .append("- Không bịa giá, giờ mở cửa, số sao. Không dùng markdown (không dùng **, #, gạch đầu dòng kiểu markdown).\n")
                .append("- Không tự sửa lịch trình; người dùng tự kéo thả thẻ hoặc bấm Thêm.\n")
                .append("- places: liệt kê MỌI địa điểm cụ thể có tên riêng mà reply nhắc tới để người dùng ghé ")
                .append("(không gồm tên thành phố/tỉnh, không gồm địa điểm đã có trong lịch trình), tối đa 6. ")
                .append("Hệ thống sẽ tự đối chiếu trên bản đồ và hiện thành thẻ, nên KHÔNG bảo người dùng gõ lệnh tìm kiếm.\n")
                .append("  category: TOURIST_ATTRACTION, RESTAURANT, CAFE hoặc ACCOMMODATION; area: khu vực nếu chắc chắn.\n");
        if (context.getAnchorName() != null) {
            system.append("- Mốc lộ trình hiện tại: ").append(sanitizePromptValue(context.getAnchorName()))
                    .append(". Ưu tiên nơi thuận đường, gần mốc này.\n");
        }

        if (isBudgetIntent(userMessage)) {
            Object budget = toolExecutor.executeTool("calculate_budget", Map.of(), context);
            system.append("\n=== NGÂN SÁCH (do hệ thống tính, dùng đúng các con số này) ===\n").append(budget).append("\n");
        }

        Map schema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "reply", Map.of("type", "STRING"),
                        "places", Map.of("type", "ARRAY", "items", Map.of(
                                "type", "OBJECT",
                                "properties", Map.of(
                                        "name", Map.of("type", "STRING"),
                                        "category", Map.of("type", "STRING"),
                                        "area", Map.of("type", "STRING")),
                                "required", List.of("name")))),
                "required", List.of("reply", "places"));

        List<Map> contents = new ArrayList<>(history);
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", userMessage))));

        GeminiClient.GeminiResult result = geminiClient.generate(system.toString(), contents, schema, 1_200);
        if (!result.ok()) {
            log.warn("Gemini (trò chuyện) thất bại: {}", result.errorReason());
            String text = result.rateLimited()
                    ? "Trợ lý AI đang quá tải hoặc đã hết lượt miễn phí, bạn thử lại sau ít phút nhé. "
                    + "Trong lúc chờ, bạn vẫn có thể gõ \"gợi ý địa điểm ...\" để xem dữ liệu đã có trong hệ thống."
                    : "Mình chưa kết nối được trợ lý AI lúc này. Bạn thử lại sau ít phút nhé.";
            return new ChatOutcome(text, List.of());
        }

        String reply;
        List<AiCandidate> mentioned = new ArrayList<>();
        try {
            JsonNode root = jsonMapper.readTree(result.text());
            reply = textOf(root, "reply");
            JsonNode places = root.get("places");
            if (places != null && places.isArray()) {
                for (JsonNode node : places) {
                    String name = limit(textOf(node, "name"), 100);
                    if (name == null) continue;
                    mentioned.add(new AiCandidate(name, limit(textOf(node, "area"), 150),
                            textOf(node, "category"), null));
                    if (mentioned.size() >= 6) break;
                }
            }
        } catch (Exception ex) {
            log.warn("Không đọc được JSON trò chuyện từ Gemini, dùng nguyên văn: {}", ex.getMessage());
            reply = result.text();
        }
        reply = stripMarkdown(limit(reply == null ? "Mình đã nhận được câu hỏi của bạn." : reply, 3_000));

        // Mọi địa điểm AI nhắc tới đều được đối chiếu trên bản đồ và hiện thành thẻ (Đã xác minh / Có trên bản đồ / Tham khảo).
        List<PlaceDiscoveryDTO> cards = mentioned.isEmpty()
                ? List.of()
                : verifyAiCandidates(mentioned, context, List.of(), mentioned.size(), "TOURIST_ATTRACTION");
        if (cards.isEmpty()) {
            return new ChatOutcome(reply, List.of());
        }
        List<PlaceDiscoveryDTO> cardList = new ArrayList<>(cards);
        cardList.forEach(place -> {
            applyTravelSignals(place, context);
            if (place.getMatchReason() == null) place.setMatchReason("AI nhắc đến trong câu trả lời");
        });
        applyCommunityRatings(cardList);
        return new ChatOutcome(reply + "\n\n" + buildTrustSummary(cardList), cardList);
    }

    /** Khung chat hiển thị văn bản thường: bỏ ký hiệu markdown phổ biến nếu model vẫn dùng. */
    private static String stripMarkdown(String text) {
        if (text == null) return null;
        return text.replace("**", "").replace("__", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[*-]\\s+", "• ")
                .trim();
    }

    // ======================================================================
    // Nhãn tin cậy & xếp hạng
    // ======================================================================

    private void applyTrustLevel(PlaceDiscoveryDTO place) {
        if (place == null) return;
        if (place.getPlaceId() == null || place.getLatitude() == null || place.getLongitude() == null) {
            place.setTrustLevel(TRUST_AI_REFERENCE);
            place.setTrustLabel(LABEL_REFERENCE);
        } else if ("PARTNER".equalsIgnoreCase(place.getProvider())) {
            // Truy vấn PARTNER chỉ trả Business đã duyệt + công khai + đang mở.
            place.setTrustLevel(TRUST_VERIFIED);
            place.setTrustLabel(LABEL_VERIFIED);
        } else {
            place.setTrustLevel(TRUST_MAP_DATA);
            place.setTrustLabel(LABEL_MAP_DATA);
        }
    }

    /** Điền điểm đánh giá cộng đồng (bảng place_reviews) cho địa điểm chưa có rating từ nguồn. */
    private void applyCommunityRatings(List<PlaceDiscoveryDTO> places) {
        Set<String> names = new HashSet<>();
        for (PlaceDiscoveryDTO place : places) {
            if (place.getName() != null) names.add(place.getName());
        }
        if (names.isEmpty()) return;

        Map<String, Object[]> stats = new HashMap<>();
        try {
            for (Object[] row : placeReviewRepository.aggregateRatingsByLocationNames(names)) {
                if (row != null && row.length >= 3 && row[0] != null) {
                    stats.put(String.valueOf(row[0]).toLowerCase(Locale.ROOT), row);
                }
            }
        } catch (Exception ex) {
            log.warn("Không đọc được đánh giá cộng đồng: {}", ex.getMessage());
            return;
        }

        for (PlaceDiscoveryDTO place : places) {
            if (place.getName() == null) continue;
            if (place.getRating() != null) {
                if (place.getRatingSource() == null) place.setRatingSource("PROVIDER");
                continue;
            }
            Object[] row = stats.get(place.getName().toLowerCase(Locale.ROOT));
            if (row == null || !(row[1] instanceof Number avg) || !(row[2] instanceof Number count)) continue;
            place.setRating(BigDecimal.valueOf(avg.doubleValue()).setScale(1, RoundingMode.HALF_UP));
            place.setUserRatingsTotal(count.intValue());
            place.setRatingSource("COMMUNITY");
        }
    }

    /**
     * Xếp hạng có lợi cho kế hoạch di chuyển:
     *   1. Nhóm tin cậy: Đã xác minh -> Có trên bản đồ (luôn ưu tiên đối tác đã xác minh).
     *   2. Trong cùng nhóm, điểm tổng hợp:
     *        30% gần địa điểm đầu tiên của lịch trình (điểm neo), 25% đánh giá cộng đồng (trung bình Bayes),
     *        25% độ nổi tiếng (có Wikipedia/Wikidata trên OSM + số lượt đánh giá), 20% điểm phù hợp sẵn có
     *        (sở thích, khoảng cách tới trung tâm, ngân sách).
     *      Thiếu dữ liệu ở tiêu chí nào thì tiêu chí đó nhận điểm trung tính, không bịa.
     */
    private void rankPlaces(List<PlaceDiscoveryDTO> places, TripContextDTO context) {
        places.forEach(place -> applyTravelSignals(place, context));
        List<PlaceDiscoveryDTO> original = new ArrayList<>(places);
        places.sort(Comparator
                .comparingInt((PlaceDiscoveryDTO p) -> trustOrder(p.getTrustLevel()))
                .thenComparing(Comparator.<PlaceDiscoveryDTO>comparingDouble(GeminiAiService::travelScore).reversed())
                .thenComparingInt(original::indexOf));
    }

    /** Gắn khoảng cách tới điểm neo và cờ "nổi tiếng" (dùng cho xếp hạng và hiển thị trên thẻ). */
    private void applyTravelSignals(PlaceDiscoveryDTO place, TripContextDTO context) {
        if (place == null) return;
        place.setNotable(place.getCategories() != null
                && place.getCategories().contains(OsmPlaceDiscoveryService.NOTABLE_CATEGORY));
        if (context != null && context.getAnchorLatitude() != null && context.getAnchorLongitude() != null
                && place.getLatitude() != null && place.getLongitude() != null) {
            place.setDistanceFromAnchorMeters(Math.round(haversineMeters(
                    context.getAnchorLatitude(), context.getAnchorLongitude(),
                    place.getLatitude(), place.getLongitude())) * 1.0);
            place.setAnchorName(context.getAnchorName());
        }
    }

    private static double travelScore(PlaceDiscoveryDTO place) {
        double proximity = place.getDistanceFromAnchorMeters() == null
                ? 0.5
                : 1.0 / (1.0 + place.getDistanceFromAnchorMeters() / 1_500.0); // 1,5 km -> 0.5
        double rating = bayesianScore(place) / 5.0;
        int reviews = place.getUserRatingsTotal() == null ? 0 : Math.max(0, place.getUserRatingsTotal());
        double popularity = Math.min(1.0, (Boolean.TRUE.equals(place.getNotable()) ? 0.5 : 0.0)
                + 0.5 * Math.log1p(reviews) / Math.log(21)); // 20 lượt đánh giá -> đủ 0.5
        double base = place.getMatchScore() == null ? 0.5 : Math.max(0.0, Math.min(1.0, place.getMatchScore()));
        return 0.30 * proximity + 0.25 * rating + 0.25 * popularity + 0.20 * base;
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(h));
    }

    private static int trustOrder(String trustLevel) {
        if (TRUST_VERIFIED.equals(trustLevel)) return 0;
        if (TRUST_MAP_DATA.equals(trustLevel)) return 1;
        return 2;
    }

    private static double bayesianScore(PlaceDiscoveryDTO place) {
        if (place.getRating() == null || place.getUserRatingsTotal() == null || place.getUserRatingsTotal() <= 0) {
            return RATING_PRIOR_MEAN;
        }
        double n = place.getUserRatingsTotal();
        return (RATING_PRIOR_WEIGHT * RATING_PRIOR_MEAN + place.getRating().doubleValue() * n) / (RATING_PRIOR_WEIGHT + n);
    }

    private static String buildTrustSummary(List<PlaceDiscoveryDTO> places) {
        long verified = places.stream().filter(p -> TRUST_VERIFIED.equals(p.getTrustLevel())).count();
        long mapData = places.stream().filter(p -> TRUST_MAP_DATA.equals(p.getTrustLevel())).count();
        long reference = places.stream().filter(p -> TRUST_AI_REFERENCE.equals(p.getTrustLevel())).count();

        List<String> parts = new ArrayList<>();
        if (verified > 0) parts.add(verified + " đối tác đã xác minh");
        if (mapData > 0) parts.add(mapData + " có trên bản đồ");
        if (reference > 0) parts.add(reference + " tham khảo từ AI (chưa xác minh vị trí)");
        return "Nguồn dữ liệu: " + String.join(" · ", parts) + ".";
    }

    // ======================================================================
    // Lưu & khôi phục thẻ gợi ý trong lịch sử
    // ======================================================================

    /**
     * action_data dạng {"items":[{"placeId":12,"reason":"...","ai":true}, {"reference":{...}}]}.
     * Mục tham khảo chỉ lưu trong lịch sử chat, không ghi vào bảng places.
     */
    private String serializeRecommendations(List<PlaceDiscoveryDTO> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) return null;
        List<Map<String, Object>> items = new ArrayList<>();
        for (PlaceDiscoveryDTO place : recommendations) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (place.getPlaceId() != null) {
                item.put("placeId", place.getPlaceId());
                if (place.getMatchReason() != null) item.put("reason", place.getMatchReason());
                if (Boolean.TRUE.equals(place.getAiSuggested())) item.put("ai", true);
            } else {
                Map<String, Object> reference = new LinkedHashMap<>();
                reference.put("name", place.getName());
                reference.put("address", place.getAddress());
                reference.put("category", place.getCategory());
                reference.put("reason", place.getMatchReason());
                reference.put("destination", place.getDestination());
                item.put("reference", reference);
            }
            items.add(item);
        }
        try {
            return jsonMapper.writeValueAsString(Map.of("items", items));
        } catch (Exception ex) {
            log.warn("Không lưu được thẻ gợi ý vào lịch sử: {}", ex.getMessage());
            return null;
        }
    }

    private List<PlaceDiscoveryDTO> restoreRecommendations(String actionData) {
        if (actionData == null || actionData.isBlank()) return Collections.emptyList();
        JsonNode root;
        try {
            root = jsonMapper.readTree(actionData);
        } catch (Exception ex) {
            return Collections.emptyList();
        }

        // Chuẩn hóa về danh sách {placeId?, reason?, ai?, reference?}; dữ liệu cũ là mảng id [1,2,3].
        List<Long> ids = new ArrayList<>();
        List<Object> ordered = new ArrayList<>(); // Long (placeId) hoặc JsonNode (item mới)
        if (root.isArray()) {
            for (JsonNode node : root) {
                long id = node.asLong(-1);
                if (id > 0) { ids.add(id); ordered.add(id); }
            }
        } else if (root.get("items") != null && root.get("items").isArray()) {
            for (JsonNode item : root.get("items")) {
                if (item.get("placeId") != null) ids.add(item.get("placeId").asLong(-1));
                ordered.add(item);
            }
        }

        Map<Long, Place> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Place place : placeRepository.findAllById(ids)) byId.put(place.getId(), place);
        }

        List<PlaceDiscoveryDTO> result = new ArrayList<>();
        for (Object entry : ordered) {
            if (entry instanceof Long id) {
                PlaceDiscoveryDTO dto = toDto(byId.get(id));
                if (dto != null) result.add(dto);
                continue;
            }
            JsonNode item = (JsonNode) entry;
            if (item.get("placeId") != null) {
                PlaceDiscoveryDTO dto = toDto(byId.get(item.get("placeId").asLong(-1)));
                if (dto == null) continue;
                String reason = textOf(item, "reason");
                if (reason != null) dto.setMatchReason(reason);
                if (item.get("ai") != null && item.get("ai").asBoolean(false)) {
                    dto.setAiSuggested(true);
                    if (TRUST_MAP_DATA.equals(dto.getTrustLevel())) dto.setTrustLabel(LABEL_MAP_DATA_AI);
                }
                result.add(dto);
            } else if (item.get("reference") != null) {
                JsonNode ref = item.get("reference");
                String name = textOf(ref, "name");
                if (name == null) continue;
                result.add(buildReference(name, textOf(ref, "address"), textOf(ref, "category"),
                        textOf(ref, "reason"), textOf(ref, "destination")));
            }
        }
        applyCommunityRatings(result);
        return result;
    }

    private PlaceDiscoveryDTO toDto(Place place) {
        PlaceDiscoveryDTO dto = place == null ? null : recommendationService.toDiscoveryDTO(place);
        if (dto != null) {
            applyTrustLevel(dto);
            applyTravelSignals(dto, null);
        }
        return dto;
    }

    // ======================================================================
    // Nhận diện yêu cầu & thu thập dữ liệu
    // ======================================================================

    private static final String[] ACCOMMODATION_TERMS =
            {"khach san", "homestay", "nha nghi", "noi luu tru", "cho o", "resort", "hostel"};
    private static final String[] CAFE_TERMS = {"quan ca phe", "ca phe", "cafe", "coffee", "quan nuoc"};
    private static final String[] RESTAURANT_TERMS =
            {"nha hang", "quan an", "mon an", "an uong", "am thuc", "quan ngon", "an gi"};

    /** Lấy dữ liệu thật (DB/OSM) theo các nhóm địa điểm người dùng nhắc tới. */
    private void collectIntentBasedRecommendations(String userMessage, TripContextDTO context,
                                                   List<PlaceDiscoveryDTO> target) {
        String normalized = normalizeVietnamese(userMessage);
        boolean accommodation = containsAny(normalized, ACCOMMODATION_TERMS);
        boolean cafe = containsAny(normalized, CAFE_TERMS);
        boolean restaurant = containsAny(normalized, RESTAURANT_TERMS);

        if (accommodation) {
            addRecommendationResults(target, toolExecutor.executeTool(
                    "search_accommodations", Map.of("limit", 8), context));
        }
        if (restaurant) {
            addRecommendationResults(target, toolExecutor.executeTool(
                    "search_restaurants", Map.of("type", "RESTAURANT", "limit", 8), context));
        }
        if (cafe) {
            addRecommendationResults(target, toolExecutor.executeTool(
                    "search_restaurants", Map.of("type", "CAFE", "limit", 8), context));
        }
        if (!accommodation && !cafe && !restaurant) {
            addRecommendationResults(target, toolExecutor.executeTool(
                    "search_places", Map.of("category", "TOURIST_ATTRACTION", "limit", 8), context));
        }
    }

    private String detectPrimaryCategory(String message) {
        String normalized = normalizeVietnamese(message);
        if (containsAny(normalized, ACCOMMODATION_TERMS)) return "ACCOMMODATION";
        if (containsAny(normalized, CAFE_TERMS)) return "CAFE";
        if (containsAny(normalized, RESTAURANT_TERMS)) return "RESTAURANT";
        return "TOURIST_ATTRACTION";
    }

    private static String normalizeCategory(String value, String fallback) {
        String upper = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "TOURIST_ATTRACTION", "ATTRACTION" -> "TOURIST_ATTRACTION";
            case "RESTAURANT", "CAFE", "ACCOMMODATION" -> upper;
            default -> fallback;
        };
    }

    private int parseRequestedCount(String message) {
        String normalized = normalizeVietnamese(message);
        Matcher digits = COUNT_DIGITS.matcher(normalized);
        if (digits.find()) {
            return clampCount(Integer.parseInt(digits.group(1)));
        }
        Matcher words = COUNT_WORDS.matcher(normalized);
        if (words.find()) {
            return clampCount(NUMBER_WORDS.getOrDefault(words.group(1), DEFAULT_PLACE_COUNT));
        }
        return DEFAULT_PLACE_COUNT;
    }

    private static int clampCount(int value) {
        return Math.max(1, Math.min(value, MAX_PLACE_COUNT));
    }

    private boolean isPlaceDiscoveryIntent(String message) {
        String normalized = normalizeVietnamese(message);
        // "Lên lịch trình / sắp xếp" là câu hỏi về lịch trình hiện có, không phải tìm địa điểm mới.
        if (containsAny(normalized, "sap xep", "len lich trinh", "toi uu lich trinh", "phan tich")) {
            return false;
        }
        // "Ngày 2 nên đi/ghé đâu, làm gì, chơi gì...": hỏi gợi ý cho một ngày cụ thể.
        if (DAY_PATTERN.matcher(normalized).find() && containsAny(normalized,
                "nen di", "nen den", "nen ghe", "goi y", "de xuat", "lam gi", "choi gi", "an gi", "o dau", "di dau")) {
            return true;
        }
        // Chỉ coi là tìm địa điểm khi có danh từ chỉ nơi chốn ("gợi ý ngân sách" không phải tìm địa điểm).
        return containsAny(normalized, "dia diem", "tham quan", "check in", "thang canh", "di dau",
                "kham pha", "noi tieng", "diem den", "choi o dau", "cho choi")
                || containsAny(normalized, ACCOMMODATION_TERMS)
                || containsAny(normalized, CAFE_TERMS)
                || containsAny(normalized, RESTAURANT_TERMS);
    }

    private boolean isBudgetIntent(String message) {
        return containsAny(normalizeVietnamese(message), "ngan sach", "chi phi", "bao nhieu tien", "budget", "chia tien");
    }

    private boolean isAlreadyInTrip(PlaceDiscoveryDTO place, TripContextDTO context) {
        if (place == null || place.getName() == null || context.getTripDays() == null) return false;
        String name = normalizeName(place.getName());
        for (TripContextDTO.TripDaySummaryDTO day : context.getTripDays()) {
            if (day.getExistingPlaces() == null) continue;
            for (String existing : day.getExistingPlaces()) {
                if (name.equals(normalizeName(existing))) return true;
            }
        }
        return false;
    }

    private void addRecommendationResults(List<PlaceDiscoveryDTO> target, Object result) {
        if (!(result instanceof List<?> items)) return;
        Set<String> keys = new HashSet<>();
        for (PlaceDiscoveryDTO existing : target) keys.add(recommendationKey(existing));
        for (Object item : items) {
            if (item instanceof PlaceDiscoveryDTO dto && dto.getName() != null && !dto.getName().isBlank()
                    && keys.add(recommendationKey(dto))) {
                target.add(dto);
            }
        }
    }

    private String recommendationKey(PlaceDiscoveryDTO dto) {
        if (dto.getPlaceId() != null) return "id:" + dto.getPlaceId();
        return normalizeName(dto.getName()) + "|" + dto.getLatitude() + "|" + dto.getLongitude();
    }

    // ======================================================================
    // Prompt & tiện ích
    // ======================================================================

    private List<Map> buildRecentHistory(Long conversationId) {
        List<AiMessage> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<String> roles = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = Math.max(0, messages.size() - HISTORY_MESSAGES_FOR_AI); i < messages.size(); i++) {
            AiMessage message = messages.get(i);
            if (message.getContent() == null || message.getContent().isBlank()) continue;
            String role = "USER".equalsIgnoreCase(message.getSenderType()) ? "user" : "model";
            String text = limit(message.getContent(), MAX_HISTORY_TEXT);
            // Gemini cần lượt user/model xen kẽ: gộp các tin liên tiếp cùng vai trò.
            if (!roles.isEmpty() && roles.get(roles.size() - 1).equals(role)) {
                texts.set(texts.size() - 1, texts.get(texts.size() - 1) + "\n" + text);
            } else {
                roles.add(role);
                texts.add(text);
            }
        }
        // Lượt đầu phải là user; lượt cuối phải là model vì tin hiện tại (user) sẽ được nối thêm sau.
        while (!roles.isEmpty() && !"user".equals(roles.get(0))) { roles.remove(0); texts.remove(0); }
        while (!roles.isEmpty() && "user".equals(roles.get(roles.size() - 1))) {
            roles.remove(roles.size() - 1);
            texts.remove(texts.size() - 1);
        }

        List<Map> history = new ArrayList<>();
        for (int i = 0; i < roles.size(); i++) {
            history.add(Map.of("role", roles.get(i), "parts", List.of(Map.of("text", texts.get(i)))));
        }
        return history;
    }

    private String buildTripSummary(TripContextDTO context) {
        StringBuilder prompt = new StringBuilder("=== CHUYẾN ĐI (nguồn sự thật, không hỏi lại) ===\n");
        prompt.append("- Điểm đến: ").append(safeDestination(context)).append("\n");
        prompt.append("- Thời gian: ").append(context.getStartDate() != null ? context.getStartDate() : "chưa đặt")
                .append(" → ").append(context.getEndDate() != null ? context.getEndDate() : "chưa đặt").append("\n");
        prompt.append("- Số người: ").append(context.getMemberCount() != null ? context.getMemberCount() : 1).append("\n");
        prompt.append("- Ngân sách: ").append(context.getTotalBudget() != null
                ? context.getTotalBudget().toPlainString() + " VND" : "chưa đặt").append("\n");
        if (context.getTripDays() != null && !context.getTripDays().isEmpty()) {
            prompt.append("- Lịch trình hiện có:\n");
            for (TripContextDTO.TripDaySummaryDTO day : context.getTripDays()) {
                prompt.append("  Ngày ").append(day.getDayNumber()).append(": ")
                        .append(day.getExistingPlaces() == null || day.getExistingPlaces().isEmpty()
                                ? "chưa có địa điểm"
                                : limit(sanitizePromptValue(String.join(", ", day.getExistingPlaces())), 300))
                        .append("\n");
            }
        }
        return prompt.toString();
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) return null;
        String value = node.get(field).asString("");
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String limit(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static String normalizeName(String value) {
        return normalizeVietnamese(value).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String normalizeVietnamese(String value) {
        if (value == null) return "";
        String normalized = java.text.Normalizer.normalize(value.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.replace('đ', 'd').replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) return true;
        }
        return false;
    }

    private static String sanitizePromptValue(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim();
    }

    private static String safeDestination(TripContextDTO context) {
        return context != null && context.getDestination() != null && !context.getDestination().isBlank()
                ? context.getDestination()
                : "điểm đến của bạn";
    }
}