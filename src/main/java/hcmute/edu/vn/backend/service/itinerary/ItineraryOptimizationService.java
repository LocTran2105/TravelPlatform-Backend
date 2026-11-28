package hcmute.edu.vn.backend.service.itinerary;

import hcmute.edu.vn.backend.dto.ItineraryOptimizationDtos.*;
import hcmute.edu.vn.backend.entity.*;
import hcmute.edu.vn.backend.repository.PartnerBusinessHoursRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessSpecialHourRepository;
import hcmute.edu.vn.backend.repository.TripDestinationRepository;
import hcmute.edu.vn.backend.service.TripAccessException;
import hcmute.edu.vn.backend.service.TripAccessService;
import hcmute.edu.vn.backend.service.TripActivityService;
import hcmute.edu.vn.backend.service.TripStayService;
import hcmute.edu.vn.backend.service.itinerary.EstimatedTravelMatrix.GeoPoint;
import hcmute.edu.vn.backend.service.itinerary.ItineraryOptimizer.Schedule;
import hcmute.edu.vn.backend.service.itinerary.ItineraryOptimizer.Visit;
import hcmute.edu.vn.backend.service.itinerary.VisitProfileResolver.PlaceFacts;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Tối ưu lộ trình của chuyến đi: nạp lịch trình, nơi ở, giờ mở cửa; gọi {@link ItineraryOptimizer} cho từng ngày;
 * diễn giải kết quả (lý do, cảnh báo, gợi ý chuyển ngày) và áp dụng khi người dùng xác nhận.
 */
@Service
@RequiredArgsConstructor
public class ItineraryOptimizationService {

    public static final String STATUS_OPTIMIZED = "OPTIMIZED";
    public static final String STATUS_UNCHANGED = "UNCHANGED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    private static final Pattern TIME_RANGE = Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})\\s*(?:-\\s*(\\d{1,2}):(\\d{2}))?\\s*$");
    private static final Pattern APPLY_TIME = Pattern.compile("^\\d{2}:\\d{2} - \\d{2}:\\d{2}$");
    private static final String[] WEEKDAY_LABELS = {"", "Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7", "Chủ nhật"};
    private static final double MOVE_MIN_SAVED_MINUTES = 10;
    private static final double MOVE_MIN_SAVED_METERS = 3000;
    private static final int MAX_MOVE_SUGGESTIONS = 5;

    private final TripAccessService accessService;
    private final TripStayService stayService;
    private final TripActivityService activityService;
    private final TripDestinationRepository destinationRepository;
    private final PartnerBusinessHoursRepository hoursRepository;
    private final PartnerBusinessSpecialHourRepository specialHoursRepository;
    private final TravelMatrixService travelMatrixService;
    private final ItineraryOptimizer optimizer = new ItineraryOptimizer();

    // ====================================================================== dữ liệu nội bộ

    /** Một điểm trong ngày kèm dữ liệu cần để dựng Stop cho bất kỳ ngày nào (gợi ý chuyển ngày). */
    private record Source(TripDestination dest, String name, double lat, double lng, PlaceFacts facts,
                          Long businessId, Integer fixedStart, Integer fixedDwell) { }

    private record Built(Stop stop, String hoursLabel) { }

    private record DayContext(TripDay day, LocalDate date, Anchor start, Anchor end, List<Source> sources,
                              List<TripDestination> excluded, List<UnplacedStop> unplaced, DayProblem problem,
                              Schedule current, Schedule best, List<String> hoursLabels) { }

    private final class HoursCache {
        private final Map<Long, List<PartnerBusinessHours>> weekly = new HashMap<>();
        private final Map<String, Optional<PartnerBusinessSpecialHour>> special = new HashMap<>();

        List<PartnerBusinessHours> weekly(Long businessId) {
            return weekly.computeIfAbsent(businessId, hoursRepository::findByBusiness_IdOrderByDayOfWeekAsc);
        }

        Optional<PartnerBusinessSpecialHour> special(Long businessId, LocalDate date) {
            return special.computeIfAbsent(businessId + "@" + date,
                    k -> specialHoursRepository.findByBusiness_IdAndSpecialDate(businessId, date));
        }
    }

    // ====================================================================== tối ưu (xem trước)

    @Transactional(readOnly = true)
    public OptimizationResponse optimize(Long tripId, OptimizeRequest request, String email) {
        Trip trip = accessService.requireMember(tripId, email);
        TripPreference preference = trip.getPreference();
        String transport = firstNonBlank(request == null ? null : request.transport(),
                preference == null ? null : preference.getPreferredTransport(), "MOTORBIKE").toUpperCase(Locale.ROOT);
        String intensity = firstNonBlank(request == null ? null : request.intensity(),
                preference == null ? null : preference.getIntensity(), "BALANCED").toUpperCase(Locale.ROOT);
        List<String> tripTags = preference == null || preference.getPreferenceTags() == null
                ? List.of() : preference.getPreferenceTags();

        List<TripDay> days = sortedDays(trip);
        Set<Long> wanted = request == null || request.dayIds() == null || request.dayIds().isEmpty()
                ? null : new HashSet<>(request.dayIds());
        List<TripStay> stays = stayService.staysOf(tripId);
        HoursCache hours = new HoursCache();

        List<DayContext> contexts = new ArrayList<>();
        List<DayPlan> plans = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            TripDay day = days.get(i);
            if (wanted != null && !wanted.contains(day.getId())) continue;
            LocalDate date = dateOf(trip, day);
            Anchor[] anchors = anchorsFor(day, i > 0 ? days.get(i - 1) : null, date, stays);
            DayContext ctx = buildContext(day, date, anchors[0], anchors[1], stays.isEmpty(), tripTags, intensity,
                    transport, hours, true);
            plans.add(toPlan(ctx));
            if (ctx.problem() != null) contexts.add(ctx);
        }

        List<MoveSuggestion> moves = contexts.size() > 1
                ? suggestMoves(contexts, tripTags, intensity, transport, hours) : List.of();
        return new OptimizationResponse(tripId, signature(trip), transport, intensity,
                travelMatrixService.googleEnabled(), plans, moves);
    }

    /** Dựng bài toán của một ngày và giải. useGoogle=false: luôn ước lượng (dùng cho gợi ý chuyển ngày). */
    private DayContext buildContext(TripDay day, LocalDate date, Anchor start, Anchor end, boolean legacyAccommodation,
                                    List<String> tripTags, String intensity, String transport, HoursCache hours,
                                    boolean useGoogle) {
        List<Source> sources = new ArrayList<>();
        List<TripDestination> excluded = new ArrayList<>();
        List<UnplacedStop> unplaced = new ArrayList<>();
        for (TripDestination dest : destinationsOf(day)) {
            if (legacyAccommodation && isAccommodation(dest)) {
                excluded.add(dest);
                continue;
            }
            Source source = sourceOf(dest, tripTags);
            if (source == null) {
                unplaced.add(new UnplacedStop(dest.getId(), dest.getPlaceName(), "Chưa có vị trí trên bản đồ, giữ ở cuối ngày."));
                excluded.add(dest);
            } else {
                sources.add(source);
            }
        }
        if (sources.isEmpty() || sources.size() > ItineraryOptimizer.MAX_STOPS) {
            return new DayContext(day, date, start, end, sources, excluded, unplaced, null, null, null, List.of());
        }
        List<Stop> stops = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Source source : sources) {
            Built built = buildStop(source, date, intensity, hours);
            stops.add(built.stop());
            labels.add(built.hoursLabel());
        }
        DayProblem problem = problemFor(stops, sources, start, end, intensity, transport, useGoogle);
        Schedule current = optimizer.simulate(problem, IntStream.range(0, stops.size()).toArray());
        Schedule best = optimizer.optimize(problem);
        return new DayContext(day, date, start, end, sources, excluded, unplaced, problem, current, best, labels);
    }

    private DayProblem problemFor(List<Stop> stops, List<Source> sources, Anchor start, Anchor end,
                                  String intensity, String transport, boolean useGoogle) {
        List<GeoPoint> points = new ArrayList<>();
        for (Source s : sources) points.add(new GeoPoint(s.lat(), s.lng()));
        Integer startNode = null;
        Integer endNode = null;
        if (start != null) {
            points.add(new GeoPoint(start.latitude(), start.longitude()));
            startNode = points.size() - 1;
        }
        if (end != null) {
            if (start != null && sameSpot(start, end)) {
                endNode = startNode;
            } else {
                points.add(new GeoPoint(end.latitude(), end.longitude()));
                endNode = points.size() - 1;
            }
        }
        TravelMatrix matrix = useGoogle
                ? travelMatrixService.compute(points, transport)
                : EstimatedTravelMatrix.compute(points, transport);
        int[] window = dayWindow(intensity);
        return new DayProblem(stops, startNode, endNode, window[0], window[1], matrix);
    }

    private Built buildStop(Source source, LocalDate date, String intensity, HoursCache hours) {
        VisitProfile profile = VisitProfileResolver.resolve(source.facts(), date, source.lat(), source.lng(), intensity);
        int dwell = source.fixedDwell() != null ? source.fixedDwell() : profile.dwellMinutes();

        List<TimeWindow> open = null;
        boolean known = false;
        String label = null;
        if (source.businessId() != null) {
            Optional<PartnerBusinessSpecialHour> special = hours.special(source.businessId(), date);
            if (special.isPresent()) {
                PartnerBusinessSpecialHour sh = special.get();
                known = true;
                open = Boolean.TRUE.equals(sh.getClosed()) || sh.getOpenTime() == null || sh.getCloseTime() == null
                        ? List.of()
                        : List.of(window(TimeWindow.of(sh.getOpenTime()), TimeWindow.of(sh.getCloseTime()), false));
            } else {
                int weekday = date.getDayOfWeek().getValue();
                List<PartnerBusinessHours> slots = hours.weekly(source.businessId()).stream()
                        .filter(h -> Objects.equals(h.getDayOfWeek(), weekday)).toList();
                if (!slots.isEmpty()) {
                    known = true;
                    open = slots.stream()
                            .filter(h -> !Boolean.TRUE.equals(h.getClosed()) && h.getOpenTime() != null && h.getCloseTime() != null)
                            .map(h -> window(TimeWindow.of(h.getOpenTime()), TimeWindow.of(h.getCloseTime()),
                                    Boolean.TRUE.equals(h.getClosesNextDay())))
                            .sorted(Comparator.comparingInt(TimeWindow::start))
                            .toList();
                }
            }
            if (known) {
                label = open.isEmpty() ? "Đóng cửa " + WEEKDAY_LABELS[date.getDayOfWeek().getValue()]
                        : String.join(", ", open.stream().map(TimeWindow::label).toList());
            }
        }
        if (!known && profile.defaultHours() != null && !profile.defaultHours().isEmpty()
                && profile.defaultHours().get(0).end() - profile.defaultHours().get(0).start() < 24 * 60) {
            label = String.join(", ", profile.defaultHours().stream().map(TimeWindow::label).toList());
        }
        Stop stop = new Stop(source.dest().getId(), source.name(), dwell, open, known, source.fixedStart(), profile);
        return new Built(stop, label);
    }

    /** Khung giờ mở cửa; đóng qua nửa đêm (hoặc 00:00) tính tới 24:00. */
    private static TimeWindow window(int open, int close, boolean nextDay) {
        int end = nextDay || close <= open ? 24 * 60 : close;
        return new TimeWindow(open, end);
    }

    private Source sourceOf(TripDestination dest, List<String> tripTags) {
        Place place = dest.getPlace();
        Double lat = toDouble(dest.getLatitude());
        Double lng = toDouble(dest.getLongitude());
        if ((lat == null || lng == null) && place != null) {
            lat = toDouble(place.getLatitude());
            lng = toDouble(place.getLongitude());
        }
        if (lat == null || lng == null) return null;

        String name = firstNonBlank(dest.getPlaceName(), place == null ? null : place.getName(), "Địa điểm");
        String type = place != null && place.getPlaceType() != null ? place.getPlaceType() : dest.getItemType();
        PlaceFacts facts = new PlaceFacts(name, type, place == null ? List.of() : place.getCategories(),
                dest.getNote(), tripTags);

        Integer fixedStart = null;
        Integer fixedDwell = null;
        Matcher m = TIME_RANGE.matcher(dest.getTimeRange() == null ? "" : dest.getTimeRange());
        if (m.matches()) {
            fixedStart = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
            if (m.group(3) != null) {
                int endMinute = Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4));
                if (endMinute > fixedStart) fixedDwell = endMinute - fixedStart;
            }
        } else if (dest.getStartTime() != null) {
            fixedStart = TimeWindow.of(dest.getStartTime());
            if (dest.getEndTime() != null && TimeWindow.of(dest.getEndTime()) > fixedStart) {
                fixedDwell = TimeWindow.of(dest.getEndTime()) - fixedStart;
            }
        }
        Long businessId = place == null ? null : place.getPartnerBusinessId();
        return new Source(dest, name, lat, lng, facts, businessId, fixedStart, fixedDwell);
    }

    // ====================================================================== diễn giải kết quả

    private DayPlan toPlan(DayContext ctx) {
        TripDay day = ctx.day();
        List<Long> currentOrder = destinationsOf(day).stream().map(TripDestination::getId).toList();
        Double sunLat = ctx.sources().isEmpty() ? null : ctx.sources().get(0).lat();
        Double sunLng = ctx.sources().isEmpty() ? null : ctx.sources().get(0).lng();
        String sunrise = null;
        String sunset = null;
        if (sunLat != null) {
            SunCalculator.SunTimes sun = SunCalculator.compute(ctx.date(), sunLat, sunLng);
            sunrise = TimeWindow.format(sun.sunrise());
            sunset = TimeWindow.format(sun.sunset());
        }

        if (ctx.problem() == null) {
            String message = ctx.sources().isEmpty()
                    ? (currentOrder.isEmpty() ? "Ngày này chưa có địa điểm." : "Các địa điểm chưa có vị trí trên bản đồ.")
                    : "Ngày này có hơn " + ItineraryOptimizer.MAX_STOPS + " địa điểm, hãy chia bớt sang ngày khác.";
            return new DayPlan(day.getId(), day.getDayNumber(), ctx.date(), STATUS_SKIPPED, message, false,
                    ctx.start(), ctx.end(), null, null, sunrise, sunset, null, currentOrder, currentOrder,
                    List.of(), ctx.unplaced(), null, null, null, List.of());
        }

        Schedule best = ctx.best();
        Schedule current = ctx.current();
        boolean changed = !Arrays.equals(best.order(), IntStream.range(0, ctx.sources().size()).toArray());

        // Thứ tự đề xuất: điểm đã xếp theo lời giải, rồi điểm lưu trú cũ/chưa có tọa độ giữ ở cuối.
        List<Long> proposedOrder = new ArrayList<>();
        for (int idx : best.order()) proposedOrder.add(ctx.sources().get(idx).dest().getId());
        for (TripDestination ex : ctx.excluded()) proposedOrder.add(ex.getId());

        List<PlannedStop> stops = new ArrayList<>();
        for (int k = 0; k < best.visits().size(); k++) {
            Visit v = best.visits().get(k);
            Source source = ctx.sources().get(v.stop());
            Stop stop = ctx.problem().stops().get(v.stop());
            stops.add(describe(source, stop, v, ctx.hoursLabels().get(v.stop()), ctx.date(),
                    currentOrder.indexOf(source.dest().getId()) + 1));
        }

        Totals before = totals(current);
        Totals after = totals(best);
        List<String> warnings = new ArrayList<>();
        if (best.overtimeMinutes() > 15) {
            warnings.add("Lịch khá dày: kết thúc muộn hơn dự kiến khoảng " + best.overtimeMinutes()
                    + " phút. Cân nhắc chuyển bớt điểm sang ngày khác.");
        }
        if (ctx.start() == null) {
            warnings.add("Chưa có nơi ở cho đêm trước ngày này: lộ trình bắt đầu từ điểm đầu tiên. Thêm ở mục \"Nơi ở\" để tối ưu cả đường đi/về.");
        }

        return new DayPlan(day.getId(), day.getDayNumber(), ctx.date(),
                changed ? STATUS_OPTIMIZED : STATUS_UNCHANGED, null, changed,
                ctx.start(), ctx.end(),
                ctx.start() != null ? TimeWindow.format(best.departAt()) : null,
                best.returnAt() != null ? TimeWindow.format(best.returnAt()) : null,
                sunrise, sunset, ctx.problem().matrix().source(),
                currentOrder, proposedOrder, stops, ctx.unplaced(), before, after,
                summary(changed, before, after), warnings);
    }

    private PlannedStop describe(Source source, Stop stop, Visit v, String hoursLabel, LocalDate date, int previousPosition) {
        VisitProfile profile = stop.profile();
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (stop.fixedStart() != null) {
            reasons.add("Giữ giờ bạn đã đặt (" + TimeWindow.format(stop.fixedStart()) + ")");
        } else if (!profile.flexible() && v.preferredMissMinutes() == 0) {
            reasons.add(profile.reason());
        }
        if (v.waitMinutes() >= 10 && stop.fixedStart() == null) {
            List<TimeWindow> open = stop.hoursKnown() ? stop.openWindows() : profile.defaultHours();
            boolean waitingForOpen = open != null && open.stream().anyMatch(w -> w.start() == v.start());
            reasons.add(waitingForOpen
                    ? "Đến sớm " + v.waitMinutes() + " phút, chờ mở cửa lúc " + TimeWindow.format(v.start())
                    : "Thong thả " + v.waitMinutes() + " phút trước giờ đẹp " + TimeWindow.format(v.start()));
        }

        for (String issue : v.issues()) {
            switch (issue) {
                case ItineraryOptimizer.ISSUE_CLOSED ->
                        warnings.add("Đóng cửa vào " + WEEKDAY_LABELS[date.getDayOfWeek().getValue()] + ", nên chuyển sang ngày khác.");
                case ItineraryOptimizer.ISSUE_AFTER_HOURS ->
                        warnings.add("Không kịp giờ mở cửa (" + hoursLabel + ").");
                case ItineraryOptimizer.ISSUE_AFTER_ESTIMATED_HOURS ->
                        warnings.add("Có thể đã đóng cửa (giờ ước tính " + hoursLabel + ").");
                case ItineraryOptimizer.ISSUE_LATE_FOR_FIXED ->
                        warnings.add("Không kịp giờ hẹn " + TimeWindow.format(stop.fixedStart()) + " (đến khoảng "
                                + TimeWindow.format(v.arrive()) + ").");
                default -> { }
            }
        }
        if (!profile.flexible() && profile.weight() >= 2 && v.preferredMissMinutes() > 45 && stop.fixedStart() == null) {
            warnings.add("Lệch khung giờ đẹp (" + profile.preferred().stream().map(TimeWindow::label)
                    .reduce((a, b) -> a + " hoặc " + b).orElse("") + ") khoảng " + v.preferredMissMinutes() + " phút.");
        }
        if (v.depart() > 24 * 60) warnings.add("Kết thúc sau nửa đêm.");

        String currentTime = blankToNull(source.dest().getTimeRange());
        String suggested = currentTime == null
                ? TimeWindow.format(Math.min(v.start(), 23 * 60 + 59)) + " - " + TimeWindow.format(Math.min(v.depart(), 23 * 60 + 59))
                : null;

        return new PlannedStop(source.dest().getId(), source.name(), source.lat(), source.lng(), previousPosition,
                TimeWindow.format(v.arrive()), TimeWindow.format(v.start()), TimeWindow.format(v.depart()),
                currentTime, suggested, stop.fixedStart() != null,
                round1(v.travelMeters() / 1000.0), (int) Math.round(v.travelMinutes()), v.waitMinutes(),
                profile.purpose(), profile.label(), hoursLabel, !stop.hoursKnown(), reasons, warnings);
    }

    private static Totals totals(Schedule s) {
        return new Totals(round1((s.travelMeters() + s.returnMeters()) / 1000.0),
                (int) Math.round(s.travelMinutes() + s.returnMinutes()), s.hardIssues());
    }

    private static String summary(boolean changed, Totals before, Totals after) {
        if (!changed) return "Thứ tự hiện tại đã hợp lý.";
        List<String> parts = new ArrayList<>();
        double savedKm = before.distanceKm() - after.distanceKm();
        int savedMinutes = before.travelMinutes() - after.travelMinutes();
        if (savedKm >= 0.1) parts.add("bớt " + formatKm(savedKm) + " km");
        if (savedMinutes >= 1) parts.add(savedMinutes + " phút di chuyển");
        if (after.issues() < before.issues()) parts.add("tránh " + (before.issues() - after.issues()) + " điểm đóng cửa/không kịp giờ");
        if (parts.isEmpty()) return "Sắp xếp lại để ghé đúng khung giờ đẹp của từng điểm.";
        return "Đổi thứ tự giúp " + String.join(", ", parts) + ".";
    }

    // ====================================================================== gợi ý chuyển ngày

    private List<MoveSuggestion> suggestMoves(List<DayContext> contexts, List<String> tripTags, String intensity,
                                              String transport, HoursCache hours) {
        List<MoveSuggestion> result = new ArrayList<>();
        for (DayContext from : contexts) {
            if (from.sources().size() < 2) continue;
            int[] fromOrder = from.best().order();
            // Đánh giá ngày nguồn bằng ước lượng để so sánh công bằng với ngày đích.
            DayProblem fromEst = problemFor(from.problem().stops(), from.sources(), from.start(), from.end(),
                    intensity, transport, false);
            double fromCost = optimizer.cost(fromEst, fromOrder);
            Schedule fromSchedule = optimizer.simulate(fromEst, fromOrder);

            for (int k = 0; k < fromOrder.length; k++) {
                int s = fromOrder[k];
                Source source = from.sources().get(s);
                if (source.fixedStart() != null) continue; // điểm có giờ hẹn: người dùng đã chốt ngày
                int[] without = Arrays.stream(fromOrder).filter(x -> x != s).toArray();
                Schedule fromWithout = optimizer.simulate(fromEst, without);
                double removalSaving = fromCost - fromWithout.cost();
                double removalMeters = (fromSchedule.travelMeters() + fromSchedule.returnMeters())
                        - (fromWithout.travelMeters() + fromWithout.returnMeters());
                boolean closedHere = fromSchedule.visits().get(k).issues().contains(ItineraryOptimizer.ISSUE_CLOSED);

                MoveSuggestion bestMove = null;
                double bestNet = 0;
                for (DayContext to : contexts) {
                    if (to == from || to.sources().size() >= ItineraryOptimizer.MAX_STOPS) continue;
                    List<Source> toSources = new ArrayList<>(to.sources());
                    toSources.add(source);
                    List<Stop> toStops = new ArrayList<>(to.problem().stops());
                    toStops.add(buildStop(source, to.date(), intensity, hours).stop());
                    DayProblem toEst = problemFor(toStops, toSources, to.start(), to.end(), intensity, transport, false);
                    int added = toStops.size() - 1;
                    int[] base = to.best().order();
                    Schedule baseSchedule = optimizer.simulate(toEst, base);

                    Schedule bestInsert = null;
                    for (int pos = 0; pos <= base.length; pos++) {
                        int[] candidate = new int[base.length + 1];
                        for (int a = 0, b = 0; a < candidate.length; a++) candidate[a] = a == pos ? added : base[b++];
                        Schedule c = optimizer.simulate(toEst, candidate);
                        if (bestInsert == null || c.cost() < bestInsert.cost()) bestInsert = c;
                    }
                    if (bestInsert == null || bestInsert.hardIssues() > baseSchedule.hardIssues()) continue;

                    double net = removalSaving - (bestInsert.cost() - baseSchedule.cost());
                    double netMeters = removalMeters - ((bestInsert.travelMeters() + bestInsert.returnMeters())
                            - (baseSchedule.travelMeters() + baseSchedule.returnMeters()));
                    boolean worth = closedHere || net >= MOVE_MIN_SAVED_MINUTES || netMeters >= MOVE_MIN_SAVED_METERS;
                    if (!worth || net <= bestNet) continue;
                    bestNet = net;
                    String reason = closedHere
                            ? "Ngày " + from.day().getDayNumber() + " quán đóng cửa, Ngày " + to.day().getDayNumber() + " mở cửa."
                            : "Gần các điểm của Ngày " + to.day().getDayNumber() + " hơn, bớt khoảng "
                                    + formatKm(Math.max(0, netMeters) / 1000.0) + " km đi lại.";
                    bestMove = new MoveSuggestion(source.dest().getId(), source.name(),
                            from.day().getId(), from.day().getDayNumber(), to.day().getId(), to.day().getDayNumber(),
                            round1(Math.max(0, netMeters) / 1000.0), (int) Math.round(Math.max(0, net)), reason);
                }
                if (bestMove != null) result.add(bestMove);
            }
        }
        result.sort(Comparator.comparingInt(MoveSuggestion::savedMinutes).reversed());
        return result.size() > MAX_MOVE_SUGGESTIONS ? result.subList(0, MAX_MOVE_SUGGESTIONS) : result;
    }

    // ====================================================================== áp dụng

    @Transactional
    public ApplyResponse apply(Long tripId, ApplyRequest request, String email) {
        Trip trip = accessService.requireEdit(tripId, email);
        if (request == null || request.days() == null || request.days().isEmpty()) {
            throw new TripAccessException(HttpStatus.BAD_REQUEST, "Thiếu lịch trình cần áp dụng.");
        }
        if (request.signature() == null || !request.signature().equals(signature(trip))) {
            throw new TripAccessException(HttpStatus.CONFLICT,
                    "Lịch trình vừa được thay đổi (có thể bởi thành viên khác). Hãy bấm tối ưu lại.");
        }

        Map<Long, TripDay> daysById = new HashMap<>();
        for (TripDay day : sortedDays(trip)) daysById.put(day.getId(), day);

        int timesFilled = 0;
        List<TripDay> touched = new ArrayList<>();
        for (ApplyDay applyDay : request.days()) {
            TripDay day = applyDay == null ? null : daysById.get(applyDay.dayId());
            if (day == null) throw new TripAccessException(HttpStatus.BAD_REQUEST, "Ngày không thuộc chuyến đi này.");
            List<TripDestination> dests = destinationsOf(day);
            List<Long> ids = applyDay.destinationIds() == null ? List.of() : applyDay.destinationIds();
            Set<Long> current = new HashSet<>();
            for (TripDestination d : dests) current.add(d.getId());
            if (ids.size() != dests.size() || !current.equals(new HashSet<>(ids))) {
                throw new TripAccessException(HttpStatus.CONFLICT,
                        "Lịch trình Ngày " + day.getDayNumber() + " vừa thay đổi. Hãy bấm tối ưu lại.");
            }
            Map<Long, TripDestination> byId = new HashMap<>();
            for (TripDestination d : dests) byId.put(d.getId(), d);
            for (int i = 0; i < ids.size(); i++) byId.get(ids.get(i)).setOrderIndex(i);

            if (applyDay.times() != null) {
                for (Map.Entry<Long, String> entry : applyDay.times().entrySet()) {
                    TripDestination dest = byId.get(entry.getKey());
                    String time = entry.getValue() == null ? "" : entry.getValue().trim();
                    // Chỉ điền giờ cho điểm đang để trống: không ghi đè giờ người dùng đã đặt.
                    if (dest == null || !APPLY_TIME.matcher(time).matches() || blankToNull(dest.getTimeRange()) != null) continue;
                    dest.setTimeRange(time);
                    timesFilled++;
                }
            }
            touched.add(day);
        }
        destinationRepository.flush();

        String label = touched.size() == 1 ? "Day " + touched.get(0).getDayNumber() : touched.size() + " ngày";
        activityService.logCoalesced(trip, email, TripActivityService.ITINERARY_OPTIMIZED, "TRIP", trip.getId(),
                null, label);
        return new ApplyResponse(touched.size(), timesFilled);
    }

    // ====================================================================== tiện ích

    /** Dấu vân tay của lịch trình (thứ tự + giờ + nơi ở) để phát hiện thay đổi giữa lúc xem trước và áp dụng. */
    private String signature(Trip trip) {
        StringBuilder sb = new StringBuilder();
        for (TripDay day : sortedDays(trip)) {
            sb.append('D').append(day.getId()).append(':');
            for (TripDestination d : destinationsOf(day)) {
                sb.append(d.getId()).append('@').append(Objects.toString(blankToNull(d.getTimeRange()), "")).append(',');
            }
        }
        for (TripStay stay : stayService.staysOf(trip.getId())) {
            sb.append('S').append(stay.getId()).append(stay.getCheckInDate()).append(stay.getCheckOutDate())
                    .append(stay.getLatitude()).append(stay.getLongitude());
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    /**
     * Điểm xuất phát = nơi ở của đêm trước (không có thì nơi ở của đêm nay); kết thúc = nơi ở của đêm nay.
     * Chuyến đi chưa khai báo nơi ở: dùng điểm lưu trú (ACCOMMODATION) cũ trong lịch trình.
     */
    private Anchor[] anchorsFor(TripDay day, TripDay previousDay, LocalDate date, List<TripStay> stays) {
        Anchor start = null;
        Anchor end = null;
        if (!stays.isEmpty()) {
            TripStay tonight = stays.stream().filter(s -> s.hasCoordinates() && s.coversNight(date)).findFirst().orElse(null);
            TripStay lastNight = stays.stream().filter(s -> s.hasCoordinates() && s.coversNight(date.minusDays(1))).findFirst().orElse(null);
            end = toAnchor(tonight);
            start = lastNight != null ? toAnchor(lastNight) : end;
        } else {
            TripDestination tonight = lastAccommodation(day);
            TripDestination lastNight = previousDay == null ? null : lastAccommodation(previousDay);
            end = toAnchor(tonight);
            start = lastNight != null ? toAnchor(lastNight) : end;
        }
        return new Anchor[]{start, end};
    }

    private TripDestination lastAccommodation(TripDay day) {
        TripDestination found = null;
        for (TripDestination d : destinationsOf(day)) {
            if (isAccommodation(d) && sourceOf(d, List.of()) != null) found = d;
        }
        return found;
    }

    private static Anchor toAnchor(TripStay stay) {
        if (stay == null) return null;
        return new Anchor(stay.getName(), stay.getLatitude().doubleValue(), stay.getLongitude().doubleValue(), "STAY");
    }

    private Anchor toAnchor(TripDestination dest) {
        if (dest == null) return null;
        Source s = sourceOf(dest, List.of());
        return s == null ? null : new Anchor(s.name(), s.lat(), s.lng(), "ACCOMMODATION");
    }

    private static boolean isAccommodation(TripDestination dest) {
        return "ACCOMMODATION".equalsIgnoreCase(dest.getItemType());
    }

    private static boolean sameSpot(Anchor a, Anchor b) {
        return Math.abs(a.latitude() - b.latitude()) < 1e-6 && Math.abs(a.longitude() - b.longitude()) < 1e-6;
    }

    private static List<TripDay> sortedDays(Trip trip) {
        List<TripDay> days = new ArrayList<>(trip.getTripDays() == null ? List.of() : trip.getTripDays());
        days.sort(Comparator.comparing(TripDay::getDayNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TripDay::getId));
        return days;
    }

    private static List<TripDestination> destinationsOf(TripDay day) {
        List<TripDestination> list = new ArrayList<>(day.getDestinations() == null ? List.of() : day.getDestinations());
        list.sort(Comparator.comparing(TripDestination::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TripDestination::getId));
        return list;
    }

    private static LocalDate dateOf(Trip trip, TripDay day) {
        if (day.getDayDate() != null) return day.getDayDate();
        if (trip.getStartDate() != null && day.getDayNumber() != null) {
            return trip.getStartDate().plusDays(day.getDayNumber() - 1L);
        }
        return LocalDate.now();
    }

    /** [đầu ngày, cuối ngày] theo nhịp độ chuyến đi. */
    private static int[] dayWindow(String intensity) {
        return switch (intensity) {
            case "RELAX" -> new int[]{8 * 60, 20 * 60 + 30};
            case "PACKED" -> new int[]{6 * 60 + 30, 22 * 60};
            default -> new int[]{7 * 60 + 30, 21 * 60};
        };
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String formatKm(double km) {
        return String.format(Locale.ROOT, "%.1f", km).replace('.', ',');
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }
}
