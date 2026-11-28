package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PostTripDtos.CategoryTotal;
import hcmute.edu.vn.backend.dto.PostTripDtos.DayPreview;
import hcmute.edu.vn.backend.dto.PostTripDtos.ExpenseLine;
import hcmute.edu.vn.backend.dto.PostTripDtos.ItineraryPreview;
import hcmute.edu.vn.backend.dto.PostTripDtos.StopPreview;
import hcmute.edu.vn.backend.dto.PostTripDtos.TripPreview;
import hcmute.edu.vn.backend.dto.TripSnapshot;
import hcmute.edu.vn.backend.entity.Expense;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripDay;
import hcmute.edu.vn.backend.entity.TripDestination;
import hcmute.edu.vn.backend.entity.TripPreference;
import hcmute.edu.vn.backend.repository.ExpenseRepository;
import hcmute.edu.vn.backend.repository.TripDayRepository;
import hcmute.edu.vn.backend.repository.TripPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Chụp lịch trình (kèm chi phí) của chuyến đi để lưu vào bài viết, và dựng dữ liệu hiển thị từ bản chụp.
 *
 * Bản chụp KHÔNG chứa: thành viên, người trả tiền, cách chia tiền, mô tả khoản chi.
 */
@Service
public class TripSnapshotService {

    static final int MAX_DAYS = 60;
    static final int MAX_STOPS = 500;
    static final int MAX_EXPENSES = 1000;

    private final TripDayRepository tripDayRepository;
    private final ExpenseRepository expenseRepository;
    private final TripPreferenceRepository preferenceRepository;
    private final CoverImageService coverImageService;

    public TripSnapshotService(TripDayRepository tripDayRepository,
                               ExpenseRepository expenseRepository,
                               TripPreferenceRepository preferenceRepository,
                               CoverImageService coverImageService) {
        this.tripDayRepository = tripDayRepository;
        this.expenseRepository = expenseRepository;
        this.preferenceRepository = preferenceRepository;
        this.coverImageService = coverImageService;
    }

    // =====================================================================
    // CHỤP
    // =====================================================================

    @Transactional(readOnly = true)
    public TripSnapshot capture(Trip trip) {
        return captureAll(List.of(trip)).get(trip.getId());
    }

    /**
     * Chụp nhiều chuyến đi cùng lúc với số câu SQL cố định (ngày + điểm đến, khoản chi, sở thích),
     * dùng cho bảng tin khi cần so sánh bản chụp với chuyến gốc.
     *
     * @throws IllegalArgumentException chuyến đi quá lớn để đăng (quá số ngày / điểm đến / khoản chi)
     */
    @Transactional(readOnly = true)
    public Map<Long, TripSnapshot> captureAll(Collection<Trip> trips) {
        Map<Long, TripSnapshot> result = new HashMap<>();
        if (trips == null || trips.isEmpty()) return result;

        List<Long> ids = trips.stream().map(Trip::getId).distinct().toList();

        Map<Long, List<TripDay>> daysByTrip = new HashMap<>();
        for (TripDay day : tripDayRepository.findAllWithDestinationsByTripIdIn(ids)) {
            daysByTrip.computeIfAbsent(day.getTrip().getId(), k -> new ArrayList<>()).add(day);
        }
        Map<Long, List<Expense>> expensesByTrip = new HashMap<>();
        for (Expense e : expenseRepository.findAllWithDestinationByTripIdIn(ids)) {
            expensesByTrip.computeIfAbsent(e.getTrip().getId(), k -> new ArrayList<>()).add(e);
        }
        Map<Long, TripPreference> prefs = new HashMap<>();
        for (TripPreference p : preferenceRepository.findAllById(ids)) {
            prefs.put(p.getTripId(), p);
        }
        coverImageService.applySuggestions(trips);

        for (Trip trip : trips) {
            result.put(trip.getId(), build(
                    trip,
                    daysByTrip.getOrDefault(trip.getId(), List.of()),
                    expensesByTrip.getOrDefault(trip.getId(), List.of()),
                    prefs.get(trip.getId())));
        }
        return result;
    }

    private TripSnapshot build(Trip trip, List<TripDay> rawDays, List<Expense> rawExpenses, TripPreference pref) {
        List<TripDay> days = rawDays.stream()
                .sorted(Comparator.comparing(TripDay::getDayNumber))
                .toList();
        if (days.size() > MAX_DAYS) {
            throw new IllegalArgumentException("Lịch trình có quá nhiều ngày để đăng (tối đa " + MAX_DAYS + " ngày).");
        }

        Map<Long, Integer> keyByDestinationId = new HashMap<>();
        int nextKey = 1;
        List<TripSnapshot.Day> snapDays = new ArrayList<>();
        for (TripDay day : days) {
            List<TripDestination> stops = day.getDestinations() == null ? List.of() : day.getDestinations().stream()
                    .sorted(Comparator.comparing(TripDestination::getOrderIndex,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(TripDestination::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            List<TripSnapshot.Stop> snapStops = new ArrayList<>();
            for (TripDestination d : stops) {
                int key = nextKey++;
                if (key > MAX_STOPS) {
                    throw new IllegalArgumentException(
                            "Lịch trình có quá nhiều địa điểm để đăng (tối đa " + MAX_STOPS + " địa điểm).");
                }
                keyByDestinationId.put(d.getId(), key);
                snapStops.add(new TripSnapshot.Stop(
                        key,
                        d.getPlace() != null ? d.getPlace().getId() : null,
                        d.getPlaceName(),
                        d.getItemType(),
                        d.getStartTime() != null ? d.getStartTime().toString() : null,
                        d.getEndTime() != null ? d.getEndTime().toString() : null,
                        d.getLatitude(),
                        d.getLongitude(),
                        d.getNote(),
                        money(d.getEstimatedCost()),
                        d.getOrderIndex(),
                        d.getTimeRange(),
                        d.getCost()));
            }
            snapDays.add(new TripSnapshot.Day(
                    day.getDayNumber(),
                    day.getDayDate() != null ? day.getDayDate().toString() : null,
                    snapStops));
        }

        if (rawExpenses.size() > MAX_EXPENSES) {
            throw new IllegalArgumentException(
                    "Chuyến đi có quá nhiều khoản chi để đăng (tối đa " + MAX_EXPENSES + " khoản).");
        }
        List<TripSnapshot.ExpenseItem> expenses = rawExpenses.stream()
                .sorted(Comparator.comparing(Expense::getId))
                .map(e -> new TripSnapshot.ExpenseItem(
                        e.getTitle(),
                        e.getCategory(),
                        money(e.getAmount()),
                        e.getTripDestination() != null ? keyByDestinationId.get(e.getTripDestination().getId()) : null))
                .toList();

        TripSnapshot.Preference preference = pref == null ? null : new TripSnapshot.Preference(
                pref.getPreferenceTags() == null ? null : List.copyOf(pref.getPreferenceTags()),
                pref.getPreferredAccommodation(),
                pref.getPreferredTransport(),
                pref.getIntensity());

        return new TripSnapshot(
                TripSnapshot.CURRENT_VERSION,
                trip.getTitle(),
                trip.getDestination(),
                trip.getStartDate() != null ? trip.getStartDate().toString() : null,
                trip.getEndDate() != null ? trip.getEndDate().toString() : null,
                trip.getMemberCount(),
                money(trip.getTotalBudget()),
                trip.getCoverImageUrl(),
                trip.getSuggestedCoverUrl(),
                preference,
                snapDays,
                expenses);
    }

    /** SHA-256 (hex) của nội dung bản chụp: hai bản chụp cùng nội dung cho cùng hash. */
    public String hash(TripSnapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(snapshot.forHash().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Bỏ số 0 thừa để "100.00" và "100" cho cùng hash; không dùng dạng mũ (1E+2). */
    static BigDecimal money(BigDecimal value) {
        if (value == null) return null;
        BigDecimal v = value.stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    // =====================================================================
    // HIỂN THỊ TỪ BẢN CHỤP
    // =====================================================================

    public TripPreview toPreview(TripSnapshot s, LocalDateTime snapshotAt, long copyCount,
                                 Boolean sourceAvailable, Boolean sourceChanged) {
        List<TripSnapshot.Day> days = days(s);
        int placeCount = days.stream().mapToInt(d -> stops(d).size()).sum();
        BigDecimal totalSpent = expenses(s).stream()
                .map(TripSnapshot.ExpenseItem::amount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int members = s.memberCount() == null || s.memberCount() < 1 ? 1 : s.memberCount();
        BigDecimal perPerson = totalSpent.signum() > 0
                ? totalSpent.divide(BigDecimal.valueOf(members), 0, RoundingMode.HALF_UP)
                : null;

        return new TripPreview(
                s.title(),
                s.destination(),
                s.startDate(),
                s.endDate(),
                days.size(),
                placeCount,
                members,
                s.totalBudget(),
                totalSpent.signum() > 0 ? totalSpent : null,
                perPerson,
                copyCount,
                s.coverImageUrl(),
                s.suggestedCoverUrl(),
                snapshotAt,
                sourceAvailable,
                sourceChanged);
    }

    public ItineraryPreview toItinerary(TripSnapshot s, TripPreview preview) {
        Map<Integer, List<ExpenseLine>> byStop = new HashMap<>();
        List<ExpenseLine> general = new ArrayList<>();
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();

        for (TripSnapshot.ExpenseItem e : expenses(s)) {
            BigDecimal amount = e.amount() == null ? BigDecimal.ZERO : e.amount();
            ExpenseLine line = new ExpenseLine(e.title(), e.category(), amount);
            if (e.stopKey() != null) {
                byStop.computeIfAbsent(e.stopKey(), k -> new ArrayList<>()).add(line);
            } else {
                general.add(line);
            }
            String category = e.category() == null ? "OTHER" : e.category();
            byCategory.merge(category, amount, BigDecimal::add);
        }

        List<DayPreview> dayPreviews = days(s).stream()
                .map(day -> new DayPreview(
                        day.dayNumber(),
                        day.dayDate(),
                        stops(day).stream().map(stop -> {
                            List<ExpenseLine> lines = byStop.getOrDefault(stop.key(), List.of());
                            BigDecimal actual = lines.isEmpty() ? null : lines.stream()
                                    .map(ExpenseLine::amount)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                            return new StopPreview(stop.placeName(), stop.itemType(), stop.startTime(),
                                    stop.endTime(), stop.note(), stop.estimatedCost(), actual, lines);
                        }).toList()))
                .toList();

        List<CategoryTotal> categories = byCategory.entrySet().stream()
                .map(en -> new CategoryTotal(en.getKey(), en.getValue()))
                .sorted(Comparator.comparing(CategoryTotal::amount).reversed())
                .toList();

        return new ItineraryPreview(preview, dayPreviews, categories, general);
    }

    static List<TripSnapshot.Day> days(TripSnapshot s) {
        return s.days() == null ? List.of() : s.days();
    }

    static List<TripSnapshot.Stop> stops(TripSnapshot.Day d) {
        return d.stops() == null ? List.of() : d.stops();
    }

    static List<TripSnapshot.ExpenseItem> expenses(TripSnapshot s) {
        return s.expenses() == null ? List.of() : s.expenses();
    }
}
