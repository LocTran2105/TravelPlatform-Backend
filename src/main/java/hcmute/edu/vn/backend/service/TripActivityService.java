package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.ActivityDtos.ActivityItem;
import hcmute.edu.vn.backend.dto.ActivityDtos.ActivityPage;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripActivity;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.TripActivityRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Lịch sử thao tác của chuyến đi.
 *
 * Ghi log nằm CÙNG giao dịch với thao tác gốc: thao tác thất bại/rollback thì không có dòng log giả.
 * Các lần sửa liên tiếp (cùng người, cùng hành động, cùng đối tượng, trong 10 phút, và chưa có ai làm gì
 * chen vào giữa) được gộp thành một dòng để lịch sử không bị ngập khi người dùng gõ ghi chú, đổi giờ...
 */
@Service
public class TripActivityService {

    public static final Duration COALESCE_WINDOW = Duration.ofMinutes(10);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    // Các loại hành động (frontend dựa vào những chuỗi này để ghép câu)
    public static final String DAY_ADDED = "DAY_ADDED";
    public static final String DESTINATION_ADDED = "DESTINATION_ADDED";
    public static final String DESTINATION_TIME_CHANGED = "DESTINATION_TIME_CHANGED";
    public static final String DESTINATION_NOTE_CHANGED = "DESTINATION_NOTE_CHANGED";
    public static final String DESTINATION_COST_CHANGED = "DESTINATION_COST_CHANGED";
    public static final String DESTINATION_VISITED = "DESTINATION_VISITED";
    public static final String DESTINATION_UNVISITED = "DESTINATION_UNVISITED";
    public static final String DESTINATION_REMOVED = "DESTINATION_REMOVED";
    public static final String DESTINATION_MOVED = "DESTINATION_MOVED";
    public static final String ITINERARY_REORDERED = "ITINERARY_REORDERED";
    public static final String EXPENSE_ADDED = "EXPENSE_ADDED";
    public static final String EXPENSE_DELETED = "EXPENSE_DELETED";
    public static final String BUDGET_CHANGED = "BUDGET_CHANGED";
    public static final String MEMBER_INVITED = "MEMBER_INVITED";
    public static final String MEMBER_JOINED = "MEMBER_JOINED";
    public static final String MEMBER_ROLE_CHANGED = "MEMBER_ROLE_CHANGED";
    public static final String MEMBER_REMOVED = "MEMBER_REMOVED";
    /** Chuyến đi được tạo bằng cách sao chép. label = tên chuyến gốc, detail = username chủ chuyến gốc. */
    public static final String TRIP_COPIED = "TRIP_COPIED";

    private final TripActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final TripRepository tripRepository;
    private final TripAccessService accessService;

    public TripActivityService(TripActivityRepository activityRepository,
                               UserRepository userRepository,
                               TripRepository tripRepository,
                               TripAccessService accessService) {
        this.activityRepository = activityRepository;
        this.userRepository = userRepository;
        this.tripRepository = tripRepository;
        this.accessService = accessService;
    }

    // ==================================================================
    // GHI LOG
    // ==================================================================

    /** Ghi một dòng mới. */
    public void log(Trip trip, User actor, String action, String targetType, Long targetId, String label, String detail) {
        write(trip, actor, action, targetType, targetId, label, detail, false);
    }

    public void log(Trip trip, String actorEmail, String action, String targetType, Long targetId, String label, String detail) {
        write(trip, findUser(actorEmail), action, targetType, targetId, label, detail, false);
    }

    /** Như log(), nhưng gộp vào dòng liền trước nếu đó là cùng người sửa cùng đối tượng gần đây. */
    public void logCoalesced(Trip trip, User actor, String action, String targetType, Long targetId, String label, String detail) {
        write(trip, actor, action, targetType, targetId, label, detail, true);
    }

    public void logCoalesced(Trip trip, String actorEmail, String action, String targetType, Long targetId, String label, String detail) {
        write(trip, findUser(actorEmail), action, targetType, targetId, label, detail, true);
    }

    private void write(Trip trip, User actor, String action, String targetType, Long targetId,
                       String label, String detail, boolean coalesce) {
        if (trip == null || trip.getId() == null) return;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Long actorId = actor != null ? actor.getId() : null;
        String cleanLabel = clean(label, 255);
        String cleanDetail = clean(detail, 500);

        if (coalesce) {
            Optional<TripActivity> latest = activityRepository.findFirstByTripIdOrderByIdDesc(trip.getId());
            if (latest.isPresent() && canMerge(latest.get(), actorId, action, targetType, targetId, now)) {
                TripActivity row = latest.get();
                row.setLabel(cleanLabel);
                row.setDetail(cleanDetail);
                row.setCreatedAt(now);
                activityRepository.save(row);
                return;
            }
        }

        TripActivity row = new TripActivity();
        row.setTripId(trip.getId());
        row.setActorId(actorId);
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setLabel(cleanLabel);
        row.setDetail(cleanDetail);
        row.setCreatedAt(now);
        activityRepository.save(row);
    }

    private static boolean canMerge(TripActivity last, Long actorId, String action, String targetType,
                                    Long targetId, LocalDateTime now) {
        return actorId != null
                && Objects.equals(last.getActorId(), actorId)
                && Objects.equals(last.getAction(), action)
                && Objects.equals(last.getTargetType(), targetType)
                && Objects.equals(last.getTargetId(), targetId)
                && last.getCreatedAt() != null
                && !last.getCreatedAt().isBefore(now.minus(COALESCE_WINDOW));
    }

    private User findUser(String email) {
        return email == null ? null : userRepository.findByEmail(email).orElse(null);
    }

    /** Bỏ ký tự điều khiển, cắt độ dài; chuỗi rỗng thành null. */
    static String clean(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\p{Cntrl}", " ").trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ==================================================================
    // ĐỌC LỊCH SỬ (chủ chuyến đi và người chỉnh sửa)
    // ==================================================================

    @Transactional(readOnly = true)
    public ActivityPage list(Long tripId, String email, Long before, Integer size) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripAccessException(HttpStatus.NOT_FOUND, "Không tìm thấy chuyến đi!"));
        String role = accessService.roleOf(trip, email);
        if (role == null) {
            throw new TripAccessException(HttpStatus.FORBIDDEN, "Bạn không phải thành viên của chuyến đi này!");
        }
        if ("VIEWER".equals(role)) {
            throw new TripAccessException(HttpStatus.FORBIDDEN,
                    "Chỉ chủ chuyến đi và người chỉnh sửa mới xem được lịch sử thao tác.");
        }

        int n = size == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        PageRequest page = PageRequest.of(0, n + 1); // lấy dư 1 dòng để biết còn trang sau không
        List<TripActivity> rows = (before == null || before <= 0)
                ? activityRepository.findByTripIdOrderByIdDesc(tripId, page)
                : activityRepository.findByTripIdAndIdLessThanOrderByIdDesc(tripId, before, page);

        boolean hasMore = rows.size() > n;
        List<TripActivity> pageRows = hasMore ? rows.subList(0, n) : rows;

        Set<Long> actorIds = new LinkedHashSet<>();
        for (TripActivity a : pageRows) if (a.getActorId() != null) actorIds.add(a.getActorId());
        Map<Long, User> actors = new HashMap<>();
        if (!actorIds.isEmpty()) {
            for (User u : userRepository.findAllById(actorIds)) actors.put(u.getId(), u);
        }

        List<ActivityItem> items = pageRows.stream().map(a -> {
            User u = a.getActorId() == null ? null : actors.get(a.getActorId());
            return new ActivityItem(a.getId(), a.getAction(), a.getActorId(),
                    u != null ? u.getFullName() : null, u != null ? u.getUsername() : null,
                    u != null ? u.getAvatar() : null,
                    a.getTargetType(), a.getTargetId(), a.getLabel(), a.getDetail(),
                    a.getCreatedAt().toInstant(ZoneOffset.UTC).toString());
        }).toList();

        Long next = hasMore && !pageRows.isEmpty() ? pageRows.get(pageRows.size() - 1).getId() : null;
        return new ActivityPage(items, next);
    }
}