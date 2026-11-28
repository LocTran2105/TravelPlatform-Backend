package hcmute.edu.vn.backend.service.itinerary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Xếp thứ tự và giờ ghé cho các điểm trong một ngày (bài toán người du lịch có khung giờ, TSP-TW).
 *
 * <p>Mỗi thứ tự được MÔ PHỎNG: rời nơi ở → đi tới điểm 1 → (chờ mở cửa / chờ giờ đẹp) → ở lại → ... → về nơi ở.
 * Chi phí = phút di chuyển + 0,3·phút chờ + trọng số·phút lệch giờ đẹp + 1000·vi phạm (đóng cửa, trễ giờ hẹn)
 * + phạt giờ mở cửa ước tính + 2·phút vượt cuối ngày. Mọi thành phần đều không âm nên chi phí của một
 * thứ tự dang dở là cận dưới, dùng để cắt nhánh.</p>
 *
 * <p>Tìm kiếm: ≤ {@value #EXACT_LIMIT} điểm duyệt mọi thứ tự (nhánh cận, kết quả tối ưu chính xác);
 * nhiều hơn thì láng giềng gần nhất + 2-opt + or-opt (heuristic, nhanh, gần tối ưu).</p>
 *
 * <p>Lớp thuần Java, không phụ thuộc Spring/DB để dễ kiểm thử.</p>
 */
public final class ItineraryOptimizer {

    public static final int MAX_STOPS = 15;
    static final int EXACT_LIMIT = 9;
    /** Sớm nhất được xuất phát (săn mây, bình minh). */
    static final int EARLIEST_DEPARTURE = 4 * 60 + 30;
    static final int LATE_TOLERANCE_MINUTES = 10;
    static final int MAX_WAIT_FOR_PREFERRED = 180;

    static final double WAIT_WEIGHT = 0.3;
    static final double HARD_PENALTY = 1000;
    static final double ESTIMATED_HOURS_WEIGHT = 1.0;
    static final double OVERTIME_WEIGHT = 2.0;
    /** Phá thế hòa nhỏ theo quãng đường: 0,5 điểm mỗi km. */
    static final double METER_WEIGHT = 0.0005;

    public static final String ISSUE_CLOSED = "CLOSED";
    public static final String ISSUE_AFTER_HOURS = "AFTER_HOURS";
    public static final String ISSUE_AFTER_ESTIMATED_HOURS = "AFTER_ESTIMATED_HOURS";
    public static final String ISSUE_LATE_FOR_FIXED = "LATE_FOR_FIXED";

    /** Kết quả của một điểm trong lịch. */
    public record Visit(int stop, int arrive, int start, int depart, double travelMinutes, double travelMeters,
                        int waitMinutes, int preferredMissMinutes, List<String> issues) { }

    /** Kết quả của một thứ tự. {@code visits} chỉ có khi mô phỏng chi tiết. */
    public record Schedule(int[] order, double cost, List<Visit> visits, int departAt, Integer returnAt,
                           double returnMinutes, double returnMeters, double travelMinutes, double travelMeters,
                           int hardIssues, int overtimeMinutes) { }

    private record Step(double depart, double arrive, double start, double end, double travelMinutes,
                        double travelMeters, double waited, double miss, int hard, double cost, List<String> issues) { }

    // ------------------------------------------------------------------ public API

    /** Thứ tự tốt nhất tìm được. Khi không tốt hơn thứ tự hiện tại thì giữ nguyên thứ tự hiện tại. */
    public Schedule optimize(DayProblem problem) {
        int n = problem.size();
        if (n > MAX_STOPS) throw new IllegalArgumentException("Quá " + MAX_STOPS + " điểm trong một ngày.");
        int[] identity = IntStream.range(0, n).toArray();
        Schedule current = simulate(problem, identity);
        if (n <= 1) return current;

        int[] best = n <= EXACT_LIMIT ? exactSearch(problem, identity, current.cost()) : heuristicSearch(problem, identity);
        Schedule proposed = simulate(problem, best);
        // Chỉ đổi khi tốt hơn rõ ràng, tránh xáo trộn lịch của người dùng vì chênh lệch không đáng kể.
        return proposed.cost() < current.cost() - 0.5 ? proposed : current;
    }

    /** Mô phỏng chi tiết một thứ tự (giờ đến, chờ, ở lại, cảnh báo). */
    public Schedule simulate(DayProblem problem, int[] order) {
        return run(problem, order, true);
    }

    /** Chỉ tính chi phí (nhanh, không tạo danh sách chi tiết). */
    public double cost(DayProblem problem, int[] order) {
        return run(problem, order, false).cost();
    }

    // ------------------------------------------------------------------ mô phỏng

    private Schedule run(DayProblem p, int[] order, boolean detailed) {
        List<Visit> visits = detailed ? new ArrayList<>(order.length) : null;
        Integer prev = p.startNode();
        double time = p.dayStart();
        double cost = 0;
        double travelMinutes = 0;
        double travelMeters = 0;
        int hard = 0;
        double departAt = p.dayStart();

        for (int i = 0; i < order.length; i++) {
            int s = order[i];
            Step step = step(p, prev, time, i == 0, s, detailed);
            if (i == 0) departAt = step.depart();
            cost += step.cost();
            travelMinutes += step.travelMinutes();
            travelMeters += step.travelMeters();
            hard += step.hard();
            if (detailed) {
                visits.add(new Visit(s, round(step.arrive()), round(step.start()), round(step.end()),
                        step.travelMinutes(), step.travelMeters(), round(step.waited()), round(step.miss()), step.issues()));
            }
            time = step.end();
            prev = s;
        }

        double returnMinutes = 0;
        double returnMeters = 0;
        Integer returnAt = null;
        double finish = time;
        if (p.endNode() != null && prev != null && !prev.equals(p.endNode())) {
            returnMinutes = p.matrix().minutes(prev, p.endNode());
            returnMeters = p.matrix().meters(prev, p.endNode());
            finish = time + returnMinutes;
            returnAt = round(finish);
            cost += returnMinutes + METER_WEIGHT * returnMeters;
        }
        int overtime = (int) Math.max(0, Math.round(finish - p.dayEnd()));
        cost += OVERTIME_WEIGHT * overtime;

        return new Schedule(order.clone(), cost, visits, round(departAt), returnAt, returnMinutes, returnMeters,
                travelMinutes, travelMeters, hard, overtime);
    }

    /** Đi từ {@code prev} (rời lúc {@code time}) tới điểm {@code s}, ghé và ở lại. */
    private Step step(DayProblem p, Integer prev, double time, boolean first, int s, boolean detailed) {
        Stop stop = p.stops().get(s);
        VisitProfile profile = stop.profile();
        double travel = prev == null ? 0 : p.matrix().minutes(prev, s);
        double meters = prev == null ? 0 : p.matrix().meters(prev, s);

        // Điểm đầu tiên: được xuất phát sớm hơn đầu ngày nếu điểm đó cần (săn mây, giờ hẹn sớm).
        double depart = time;
        if (first) {
            depart = p.dayStart();
            Integer desired = desiredArrival(stop);
            if (desired != null && desired - travel < depart) {
                depart = Math.max(EARLIEST_DEPARTURE, desired - travel);
            }
        }
        double arrive = depart + travel;
        double start = arrive;
        int hard = 0;
        double softHours = 0;
        List<String> issues = detailed ? new ArrayList<>(2) : null;

        if (stop.fixedStart() != null) {
            int fixed = stop.fixedStart();
            if (arrive > fixed + LATE_TOLERANCE_MINUTES) {
                hard++;
                if (detailed) issues.add(ISSUE_LATE_FOR_FIXED);
            } else {
                start = Math.max(arrive, fixed);
            }
        } else {
            if (profile.waitForPreferred() && profile.preferred() != null) {
                for (TimeWindow w : profile.preferred()) {
                    if (w.end() < arrive) continue;
                    if (w.start() > arrive && w.start() - arrive <= MAX_WAIT_FOR_PREFERRED) start = w.start();
                    break;
                }
            }
            List<TimeWindow> hours = stop.hoursKnown() ? stop.openWindows() : profile.defaultHours();
            if (hours != null) {
                if (hours.isEmpty()) {
                    if (stop.hoursKnown()) {
                        hard++;
                        if (detailed) issues.add(ISSUE_CLOSED);
                    }
                } else {
                    Double fit = null;
                    for (TimeWindow w : hours) {
                        double candidate = Math.max(start, w.start());
                        double needed = Math.min(stop.dwellMinutes(), Math.max(15, w.end() - w.start()));
                        if (candidate + needed <= w.end()) {
                            fit = candidate;
                            break;
                        }
                    }
                    if (fit != null) {
                        start = fit;
                    } else if (stop.hoursKnown()) {
                        hard++;
                        if (detailed) issues.add(ISSUE_AFTER_HOURS);
                    } else {
                        int lastEnd = hours.get(hours.size() - 1).end();
                        softHours = Math.max(0, start + stop.dwellMinutes() - lastEnd);
                        if (detailed) issues.add(ISSUE_AFTER_ESTIMATED_HOURS);
                    }
                }
            }
        }

        double miss = 0;
        if (!profile.flexible()) {
            miss = Double.MAX_VALUE;
            for (TimeWindow w : profile.preferred()) miss = Math.min(miss, w.distanceTo((int) Math.round(start)));
        }
        double wait = start - arrive;
        double end = start + stop.dwellMinutes();
        double cost = travel + WAIT_WEIGHT * wait + profile.weight() * miss + HARD_PENALTY * hard
                + ESTIMATED_HOURS_WEIGHT * softHours + METER_WEIGHT * meters;
        return new Step(depart, arrive, start, end, travel, meters, wait, miss, hard, cost, issues);
    }

    /** Giờ muốn có mặt nếu sớm hơn đầu ngày: giờ hẹn, hoặc đầu khung giờ đẹp (bình minh, săn mây). */
    private static Integer desiredArrival(Stop stop) {
        if (stop.fixedStart() != null) return stop.fixedStart();
        VisitProfile profile = stop.profile();
        if (profile.waitForPreferred() && profile.preferred() != null && !profile.preferred().isEmpty()) {
            return profile.preferred().get(0).start();
        }
        return null;
    }

    // ------------------------------------------------------------------ tìm kiếm chính xác (nhánh cận)

    private int[] exactSearch(DayProblem p, int[] initial, double initialCost) {
        int n = p.size();
        int[] path = new int[n];
        boolean[] used = new boolean[n];
        Best best = new Best(initial.clone(), initialCost + 1e-9);
        dfs(p, 0, p.startNode(), p.dayStart(), 0, path, used, best);
        return best.order;
    }

    private static final class Best {
        int[] order;
        double cost;

        Best(int[] order, double cost) {
            this.order = order;
            this.cost = cost;
        }
    }

    private void dfs(DayProblem p, int depth, Integer prev, double time, double cost,
                     int[] path, boolean[] used, Best best) {
        int n = p.size();
        if (depth == n) {
            double total = cost;
            double finish = time;
            if (p.endNode() != null && prev != null) {
                double back = p.matrix().minutes(prev, p.endNode());
                total += back + METER_WEIGHT * p.matrix().meters(prev, p.endNode());
                finish += back;
            }
            total += OVERTIME_WEIGHT * Math.max(0, Math.round(finish - p.dayEnd()));
            if (total < best.cost) {
                best.cost = total;
                best.order = path.clone();
            }
            return;
        }
        for (int s = 0; s < n; s++) {
            if (used[s]) continue;
            Step step = step(p, prev, time, depth == 0, s, false);
            double next = cost + step.cost();
            if (next >= best.cost) continue; // cận dưới đã tệ hơn lời giải tốt nhất
            used[s] = true;
            path[depth] = s;
            dfs(p, depth + 1, s, step.end(), next, path, used, best);
            used[s] = false;
        }
    }

    // ------------------------------------------------------------------ heuristic (nhiều điểm)

    private int[] heuristicSearch(DayProblem p, int[] initial) {
        Set<String> seen = new LinkedHashSet<>();
        List<int[]> starts = new ArrayList<>();
        addStart(starts, seen, initial);
        addStart(starts, seen, byPreferredTime(p));
        if (p.startNode() != null) {
            addStart(starts, seen, nearestNeighbor(p, p.startNode(), -1));
        } else {
            for (int first = 0; first < p.size(); first++) addStart(starts, seen, nearestNeighbor(p, null, first));
        }

        int[] best = initial;
        double bestCost = cost(p, initial);
        for (int[] start : starts) {
            int[] improved = localSearch(p, start);
            double c = cost(p, improved);
            if (c < bestCost) {
                bestCost = c;
                best = improved;
            }
        }
        return best;
    }

    private static void addStart(List<int[]> starts, Set<String> seen, int[] order) {
        if (seen.add(Arrays.toString(order))) starts.add(order);
    }

    /** Điểm có khung giờ đẹp sớm đi trước, điểm linh hoạt xếp sau. */
    private static int[] byPreferredTime(DayProblem p) {
        return IntStream.range(0, p.size()).boxed()
                .sorted(Comparator.comparingInt(i -> {
                    Stop stop = p.stops().get(i);
                    if (stop.fixedStart() != null) return stop.fixedStart();
                    VisitProfile profile = stop.profile();
                    return profile.flexible() ? 12 * 60 : profile.preferred().get(0).start();
                }))
                .mapToInt(Integer::intValue).toArray();
    }

    private static int[] nearestNeighbor(DayProblem p, Integer from, int forcedFirst) {
        int n = p.size();
        boolean[] used = new boolean[n];
        int[] order = new int[n];
        Integer prev = from;
        for (int k = 0; k < n; k++) {
            int pick = -1;
            if (k == 0 && forcedFirst >= 0) {
                pick = forcedFirst;
            } else {
                double bestMinutes = Double.MAX_VALUE;
                for (int s = 0; s < n; s++) {
                    if (used[s]) continue;
                    double d = prev == null ? 0 : p.matrix().minutes(prev, s);
                    if (d < bestMinutes) {
                        bestMinutes = d;
                        pick = s;
                    }
                }
            }
            used[pick] = true;
            order[k] = pick;
            prev = pick;
        }
        return order;
    }

    /** 2-opt (đảo đoạn) + or-opt (dời đoạn 1–3 điểm) cho tới khi không cải thiện được nữa. */
    private int[] localSearch(DayProblem p, int[] start) {
        int n = start.length;
        int[] current = start.clone();
        double currentCost = cost(p, current);
        boolean improved = true;
        int passes = 0;
        while (improved && passes++ < 200) {
            improved = false;
            for (int i = 0; i < n - 1 && !improved; i++) {
                for (int j = i + 1; j < n && !improved; j++) {
                    int[] candidate = current.clone();
                    reverse(candidate, i, j);
                    double c = cost(p, candidate);
                    if (c < currentCost - 1e-6) {
                        current = candidate;
                        currentCost = c;
                        improved = true;
                    }
                }
            }
            for (int len = 1; len <= 3 && !improved; len++) {
                for (int i = 0; i + len <= n && !improved; i++) {
                    for (int to = 0; to <= n - len && !improved; to++) {
                        if (to == i) continue;
                        int[] candidate = moveSegment(current, i, len, to);
                        double c = cost(p, candidate);
                        if (c < currentCost - 1e-6) {
                            current = candidate;
                            currentCost = c;
                            improved = true;
                        }
                    }
                }
            }
        }
        return current;
    }

    private static void reverse(int[] a, int i, int j) {
        while (i < j) {
            int t = a[i];
            a[i++] = a[j];
            a[j--] = t;
        }
    }

    /** Cắt đoạn [i, i+len) rồi chèn vào vị trí {@code to} của mảng còn lại. */
    private static int[] moveSegment(int[] a, int i, int len, int to) {
        List<Integer> rest = new ArrayList<>(a.length);
        List<Integer> segment = new ArrayList<>(len);
        for (int k = 0; k < a.length; k++) {
            if (k >= i && k < i + len) segment.add(a[k]);
            else rest.add(a[k]);
        }
        rest.addAll(Math.min(to, rest.size()), segment);
        return rest.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int round(double minutes) {
        return (int) Math.round(minutes);
    }
}
