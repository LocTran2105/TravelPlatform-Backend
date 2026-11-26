package hcmute.edu.vn.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giới hạn số lần gọi Gemini theo từng người dùng (cửa sổ trượt, lưu trong bộ nhớ).
 * Mục đích: một tài khoản không thể dùng hết hạn mức miễn phí chung của cả hệ thống.
 * Khi vượt giới hạn, trợ lý vẫn trả dữ liệu có sẵn trong DB, chỉ không gọi AI.
 * Lưu ý: bộ đếm mất khi khởi động lại backend và không chia sẻ giữa nhiều máy chủ; đủ cho đồ án.
 */
@Component
public class AiRateLimiter {

    @Value("${ai.rate-limit.per-window:8}")
    private int maxPerWindow;

    @Value("${ai.rate-limit.window-minutes:5}")
    private long windowMinutes;

    @Value("${ai.rate-limit.per-day:60}")
    private int maxPerDay;

    private final Map<Long, Deque<Instant>> calls = new ConcurrentHashMap<>();

    /** Ghi nhận một lượt nếu còn hạn mức; trả false nếu người dùng đã vượt giới hạn. */
    public boolean tryAcquire(Long userId) {
        if (userId == null) return false;
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofMinutes(Math.max(1, windowMinutes)));
        Instant dayStart = now.minus(Duration.ofDays(1));

        Deque<Instant> history = calls.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (history) {
            while (!history.isEmpty() && history.peekFirst().isBefore(dayStart)) {
                history.pollFirst();
            }
            long inWindow = history.stream().filter(time -> time.isAfter(windowStart)).count();
            if (inWindow >= maxPerWindow || history.size() >= maxPerDay) {
                return false;
            }
            history.addLast(now);
            return true;
        }
    }

    public String describeLimit() {
        return "tối đa " + maxPerWindow + " lượt/" + windowMinutes + " phút, " + maxPerDay + " lượt/ngày";
    }
}