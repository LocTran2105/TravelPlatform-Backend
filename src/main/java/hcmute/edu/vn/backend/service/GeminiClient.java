package hcmute.edu.vn.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gọi Gemini generateContent với:
 * - xoay vòng nhiều key (mỗi key thuộc một tài khoản/project khác nhau thì mới có thêm hạn mức);
 *   key đang bị 429/503/403 thì thử ngay key kế tiếp trong CÙNG yêu cầu;
 * - tắt "thinking" mặc định (thinkingBudget = 0) để tiết kiệm token trên gói miễn phí;
 * - chế độ JSON (responseSchema) khi cần kết quả có cấu trúc;
 * - API key gửi qua header x-goog-api-key, không nằm trong URL nên không lọt vào log.
 */
@Slf4j
@Component
@SuppressWarnings({"unchecked", "rawtypes"})
public class GeminiClient {

    private static final String DEFAULT_GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent";

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

    /** 0 = tắt suy nghĩ (nhanh, ít token). -1 = để model tự quyết (tốn token hơn nhiều). */
    @Value("${gemini.api.thinking-budget:0}")
    private int thinkingBudget;

    /** Key bắt đầu cho yêu cầu kế tiếp: chia đều tải cho các tài khoản. */
    private final AtomicInteger nextKeyIndex = new AtomicInteger();

    private final RestTemplate restTemplate = createRestTemplate();

    /** Kết quả một lần gọi. ok=false thì text=null và status là mã lỗi cuối cùng (0 = lỗi mạng/cấu hình). */
    public record GeminiResult(boolean ok, String text, int status, String errorReason) {
        static GeminiResult success(String text) {
            return new GeminiResult(true, text, 200, null);
        }

        static GeminiResult failure(int status, String reason) {
            return new GeminiResult(false, null, status, reason);
        }

        public boolean rateLimited() {
            return status == 429 || status == 503;
        }
    }

    public boolean hasAnyKey() {
        return !configuredKeys().isEmpty();
    }

    /**
     * @param systemPrompt   hướng dẫn hệ thống
     * @param contents       lịch sử hội thoại dạng Gemini ({role, parts:[{text}]})
     * @param responseSchema schema JSON (kiểu Gemini: OBJECT/STRING/...) hoặc null để nhận văn bản thường
     * @param maxOutputTokens giới hạn token đầu ra
     */
    public GeminiResult generate(String systemPrompt, List<Map> contents, Map responseSchema, int maxOutputTokens) {
        List<String> keys = configuredKeys();
        if (keys.isEmpty()) {
            return GeminiResult.failure(0, "Chưa cấu hình Gemini API Key.");
        }

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.4);
        generationConfig.put("maxOutputTokens", maxOutputTokens);
        generationConfig.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudget));
        if (responseSchema != null) {
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseSchema", responseSchema);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        body.put("contents", contents);
        body.put("generationConfig", generationConfig);

        int start = Math.floorMod(nextKeyIndex.getAndIncrement(), keys.size());
        GeminiResult last = GeminiResult.failure(0, "Không gọi được Gemini.");

        for (int attempt = 0; attempt < keys.size(); attempt++) {
            int keyIndex = (start + attempt) % keys.size();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", keys.get(keyIndex));

            try {
                ResponseEntity<Map> response = restTemplate.postForEntity(
                        resolveApiUrl(), new HttpEntity<>(body, headers), Map.class);
                String text = extractText(response.getBody());
                if (text == null || text.isBlank()) {
                    // Bị chặn bởi bộ lọc an toàn hoặc hết token đầu ra: thử key khác cũng vô ích.
                    return GeminiResult.failure(200, "Gemini không trả nội dung (" + finishReason(response.getBody()) + ").");
                }
                return GeminiResult.success(text);

            } catch (HttpStatusCodeException ex) {
                int status = ex.getStatusCode().value();
                log.warn("Gemini key #{} trả HTTP {}: {}", keyIndex + 1, status, sanitize(ex.getResponseBodyAsString()));
                last = GeminiResult.failure(status, "HTTP " + status);
                // 429 hết hạn mức, 503 quá tải, 500 lỗi tạm thời, 403 key bị khóa/sai project -> thử key khác.
                if (status != 429 && status != 503 && status != 500 && status != 403) {
                    return last;
                }
            } catch (Exception ex) {
                log.warn("Gọi Gemini bằng key #{} thất bại: {}", keyIndex + 1, sanitize(ex.getMessage()));
                last = GeminiResult.failure(0, ex.getClass().getSimpleName());
            }
        }
        return last;
    }

    private List<String> configuredKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : new String[]{primaryKey, fallbackKey1, fallbackKey2, fallbackKey3}) {
            if (key != null && !key.isBlank() && !key.contains("${")) {
                keys.add(key.trim());
            }
        }
        return new ArrayList<>(keys);
    }

    private String resolveApiUrl() {
        if (apiUrlBase != null && !apiUrlBase.isBlank() && !apiUrlBase.contains("[")) {
            return apiUrlBase.trim();
        }
        return DEFAULT_GEMINI_URL;
    }

    private static String extractText(Map body) {
        if (body == null || !(body.get("candidates") instanceof List candidates) || candidates.isEmpty()) {
            return null;
        }
        if (!(candidates.get(0) instanceof Map first) || !(first.get("content") instanceof Map content)
                || !(content.get("parts") instanceof List parts)) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof Map map && map.get("text") != null && !Boolean.TRUE.equals(map.get("thought"))) {
                text.append(map.get("text"));
            }
        }
        return text.toString().trim();
    }

    private static String finishReason(Map body) {
        if (body != null && body.get("candidates") instanceof List candidates && !candidates.isEmpty()
                && candidates.get(0) instanceof Map first && first.get("finishReason") != null) {
            return String.valueOf(first.get("finishReason"));
        }
        if (body != null && body.get("promptFeedback") instanceof Map feedback && feedback.get("blockReason") != null) {
            return "blocked: " + feedback.get("blockReason");
        }
        return "không rõ lý do";
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        String cleaned = value
                .replaceAll("(?i)([?&]key=)[^&\\s]+", "$1[REDACTED]")
                .replaceAll("AIza[0-9A-Za-z_-]{20,}", "[REDACTED_KEY]")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(40_000);
        return new RestTemplate(factory);
    }
}