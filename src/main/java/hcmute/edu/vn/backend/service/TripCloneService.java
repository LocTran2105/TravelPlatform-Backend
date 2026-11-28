package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.TripSnapshot;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripDay;
import hcmute.edu.vn.backend.entity.TripDestination;
import hcmute.edu.vn.backend.entity.TripMember;
import hcmute.edu.vn.backend.entity.TripPreference;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.PlaceRepository;
import hcmute.edu.vn.backend.repository.TripPreferenceRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tạo chuyến đi MỚI (Riêng tư) cho người sao chép, từ:
 *   - một chuyến đi (API cũ /api/trips/{id}/clone), hoặc
 *   - bản chụp lịch trình trong bài viết (/api/posts/{id}/copy-trip).
 * KHÔNG kiểm tra quyền ở đây: nơi gọi phải kiểm tra trước.
 *
 * Được chép : các ngày, các điểm đến (loại / giờ / ghi chú / chi phí dự kiến), ngân sách,
 *             sở thích chuyến đi, ảnh bìa.
 * Không chép: các khoản chi thực tế (tiền người khác đã tiêu), thành viên, lời mời, bình luận,
 *             đánh giá, lịch sử, link chia sẻ, hội thoại AI, trạng thái "đã ghé thăm".
 */
@Service
public class TripCloneService {

    static final String COPY_PREFIX = "Bản sao của: ";
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_DAYS = 60;

    private final TripRepository tripRepository;
    private final TripPreferenceRepository preferenceRepository;
    private final PlaceRepository placeRepository;
    private final TripActivityService activityService;
    private final NotificationEvents notificationEvents;

    public TripCloneService(TripRepository tripRepository,
                            TripPreferenceRepository preferenceRepository,
                            PlaceRepository placeRepository,
                            TripActivityService activityService,
                            NotificationEvents notificationEvents) {
        this.tripRepository = tripRepository;
        this.preferenceRepository = preferenceRepository;
        this.placeRepository = placeRepository;
        this.activityService = activityService;
        this.notificationEvents = notificationEvents;
    }

    // =====================================================================
    // TỪ CHUYẾN ĐI
    // =====================================================================

    /**
     * @param title     tên chuyến đi mới; trống = "Bản sao của: <tên gốc>"
     * @param startDate ngày bắt đầu mới, các ngày sau dời theo; null = giữ nguyên ngày của chuyến gốc
     */
    @Transactional
    public Trip copy(Trip source, User newOwner, String title, LocalDate startDate) {
        validateStartDate(startDate);

        List<TripDay> sourceDays = source.getTripDays() == null
                ? List.of()
                : source.getTripDays().stream()
                        .sorted(Comparator.comparing(TripDay::getDayNumber))
                        .limit(MAX_DAYS)
                        .toList();

        Trip copy = newTripShell(newOwner, resolveTitle(title, source.getTitle()), source.getDestination(),
                source.getTotalBudget(), source.getCoverImageUrl());
        copy.setClonedFromTripId(source.getId());

        int lastDayNumber = sourceDays.isEmpty() ? 1 : sourceDays.get(sourceDays.size() - 1).getDayNumber();
        applyDates(copy, startDate, source.getStartDate(), source.getEndDate(), lastDayNumber);

        for (TripDay sourceDay : sourceDays) {
            TripDay day = newDay(copy, sourceDay.getDayNumber(),
                    startDate != null ? startDate.plusDays(sourceDay.getDayNumber() - 1L) : sourceDay.getDayDate());
            if (sourceDay.getDestinations() != null) {
                for (TripDestination src : sourceDay.getDestinations()) {
                    TripDestination d = new TripDestination();
                    d.setTripDay(day);
                    d.setPlace(src.getPlace());
                    d.setItemType(src.getItemType());
                    d.setStartTime(src.getStartTime());
                    d.setEndTime(src.getEndTime());
                    d.setPlaceName(src.getPlaceName());
                    d.setLatitude(src.getLatitude());
                    d.setLongitude(src.getLongitude());
                    d.setNote(src.getNote());
                    d.setEstimatedCost(src.getEstimatedCost());
                    d.setOrderIndex(src.getOrderIndex());
                    d.setTimeRange(src.getTimeRange());
                    d.setCost(src.getCost());
                    addStop(day, d);
                }
            }
            copy.getTripDays().add(day);
        }

        TripPreference sp = source.getPreference();
        Trip saved = save(copy, sp == null ? null : new TripSnapshot.Preference(
                sp.getPreferenceTags(), sp.getPreferredAccommodation(), sp.getPreferredTransport(), sp.getIntensity()));

        User sourceOwner = source.getUser();
        activityService.log(saved, newOwner, TripActivityService.TRIP_COPIED, "TRIP", source.getId(),
                source.getTitle(), sourceOwner != null ? sourceOwner.getUsername() : null);
        // Gửi sau khi commit; tự bỏ qua khi người chép chính là chủ chuyến gốc.
        notificationEvents.onTripCopied(newOwner.getId(), source.getId());
        return saved;
    }

    // =====================================================================
    // TỪ BẢN CHỤP TRONG BÀI VIẾT
    // =====================================================================

    @Transactional
    public Trip copyFromSnapshot(Post post, User newOwner, String title, LocalDate startDate) {
        TripSnapshot s = post.getTripSnapshot();
        if (s == null) {
            throw new IllegalArgumentException("Bài viết không có lịch trình để sao chép.");
        }
        validateStartDate(startDate);

        List<TripSnapshot.Day> days = TripSnapshotService.days(s).stream()
                .filter(d -> d.dayNumber() != null && d.dayNumber() >= 1)
                .sorted(Comparator.comparing(TripSnapshot.Day::dayNumber))
                .limit(MAX_DAYS)
                .toList();

        // Chỉ gắn lại địa điểm còn tồn tại (địa điểm đã bị xóa thì giữ tên + tọa độ, place = null).
        List<Long> placeIds = days.stream()
                .flatMap(d -> TripSnapshotService.stops(d).stream())
                .map(TripSnapshot.Stop::placeId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Place> places = new HashMap<>();
        if (!placeIds.isEmpty()) {
            placeRepository.findAllById(placeIds).forEach(p -> places.put(p.getId(), p));
        }

        // Ngân sách: chỉ mang ngân sách sang (nếu có). Khoản chi thực tế của tác giả KHÔNG được chép.
        Trip copy = newTripShell(newOwner, resolveTitle(title, s.title()), s.destination(),
                s.totalBudget(), s.coverImageUrl());
        copy.setClonedFromPostId(post.getId());
        Trip sourceTrip = post.getTrip();
        if (sourceTrip != null) {
            copy.setClonedFromTripId(sourceTrip.getId());
        }

        int lastDayNumber = days.isEmpty() ? 1 : days.get(days.size() - 1).dayNumber();
        applyDates(copy, startDate, parseDate(s.startDate()), parseDate(s.endDate()), lastDayNumber);

        for (TripSnapshot.Day sd : days) {
            TripDay day = newDay(copy, sd.dayNumber(),
                    startDate != null ? startDate.plusDays(sd.dayNumber() - 1L) : parseDate(sd.dayDate()));
            for (TripSnapshot.Stop st : TripSnapshotService.stops(sd)) {
                if (st.placeName() == null || st.placeName().isBlank()) continue;
                TripDestination d = new TripDestination();
                d.setTripDay(day);
                d.setPlace(st.placeId() != null ? places.get(st.placeId()) : null);
                d.setItemType(st.itemType());
                LocalTime start = parseTime(st.startTime());
                LocalTime end = parseTime(st.endTime());
                // Ràng buộc DB: end_time >= start_time
                if (start != null && end != null && end.isBefore(start)) end = null;
                d.setStartTime(start);
                d.setEndTime(end);
                d.setPlaceName(st.placeName());
                d.setLatitude(st.latitude());
                d.setLongitude(st.longitude());
                d.setNote(st.note());
                d.setEstimatedCost(st.estimatedCost() != null && st.estimatedCost().signum() >= 0
                        ? st.estimatedCost() : null);
                d.setOrderIndex(st.orderIndex());
                d.setTimeRange(st.timeRange());
                d.setCost(st.cost());
                addStop(day, d);
            }
            copy.getTripDays().add(day);
        }

        Trip saved = save(copy, s.preference());

        User author = post.getUser();
        activityService.log(saved, newOwner, TripActivityService.TRIP_COPIED, "POST", post.getId(),
                s.title(), author != null ? author.getUsername() : null);
        // Báo cho tác giả bài (sau khi commit; tự bỏ qua khi tác giả tự chép bài của mình).
        notificationEvents.onPostTripCopied(newOwner.getId(), post.getId());
        return saved;
    }

    // =====================================================================
    // HELPER
    // =====================================================================

    private static Trip newTripShell(User owner, String title, String destination,
                                     BigDecimal budget, String coverImageUrl) {
        Trip copy = new Trip();
        copy.setUser(owner);
        copy.setTitle(title);
        copy.setDestination(destination);
        copy.setTotalBudget(budget != null && budget.signum() >= 0 ? budget : BigDecimal.ZERO);
        copy.setCoverImageUrl(coverImageUrl);
        copy.setMemberCount(1);
        copy.setVisibility("PRIVATE");
        copy.setIsPublic(false);
        copy.setPublicStatus("DRAFT");
        copy.setAverageRating(0.0);
        copy.setTotalReviews(0);

        // Chủ chuyến đi mới là thành viên đầu tiên (giống TripService.createTrip).
        copy.setMembers(new ArrayList<>());
        TripMember member = new TripMember();
        member.setTrip(copy);
        member.setUser(owner);
        member.setRole("OWNER");
        member.setStatus("ACCEPTED");
        copy.getMembers().add(member);

        copy.setTripDays(new ArrayList<>());
        return copy;
    }

    /** Có ngày bắt đầu mới: Ngày N = startDate + (N - 1). Không có: giữ ngày gốc. */
    private static void applyDates(Trip copy, LocalDate startDate, LocalDate sourceStart, LocalDate sourceEnd,
                                   int lastDayNumber) {
        if (startDate != null) {
            copy.setStartDate(startDate);
            copy.setEndDate(startDate.plusDays(Math.max(0, lastDayNumber - 1)));
        } else {
            copy.setStartDate(sourceStart);
            copy.setEndDate(sourceEnd != null && sourceStart != null && sourceEnd.isBefore(sourceStart)
                    ? sourceStart : sourceEnd);
        }
    }

    private static TripDay newDay(Trip trip, int dayNumber, LocalDate date) {
        TripDay day = new TripDay();
        day.setTrip(trip);
        day.setDayNumber(dayNumber);
        day.setDayDate(date);
        day.setDestinations(new ArrayList<>());
        return day;
    }

    private static void addStop(TripDay day, TripDestination d) {
        if (d.getItemType() == null || d.getItemType().isBlank()) d.setItemType("ATTRACTION");
        d.setIsVisited(false);
        day.getDestinations().add(d);
    }

    /** Lưu chuyến đi (cascade ngày + điểm đến + thành viên) rồi lưu sở thích. */
    private Trip save(Trip copy, TripSnapshot.Preference preference) {
        if (copy.getTripDays().isEmpty()) {
            copy.getTripDays().add(newDay(copy, 1,
                    copy.getStartDate() != null ? copy.getStartDate() : LocalDate.now()));
        }
        Trip saved = tripRepository.save(copy);

        if (preference != null) {
            TripPreference pref = new TripPreference();
            pref.setTrip(saved);
            pref.setPreferenceTags(preference.tags() == null ? null : new ArrayList<>(preference.tags()));
            pref.setPreferredAccommodation(preference.accommodation());
            pref.setPreferredTransport(preference.transport());
            pref.setIntensity(preference.intensity() != null ? preference.intensity() : "BALANCED");
            preferenceRepository.save(pref);
        }
        return saved;
    }

    private static void validateStartDate(LocalDate startDate) {
        if (startDate != null && (startDate.getYear() < 2000 || startDate.getYear() > 2100)) {
            throw new IllegalArgumentException("Ngày bắt đầu không hợp lệ.");
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String resolveTitle(String requested, String sourceTitle) {
        String value = requested == null ? "" : requested.trim();
        if (value.isEmpty()) {
            value = COPY_PREFIX + (sourceTitle == null ? "Chuyến đi" : sourceTitle);
        }
        return value.length() > MAX_TITLE_LENGTH ? value.substring(0, MAX_TITLE_LENGTH) : value;
    }
}
