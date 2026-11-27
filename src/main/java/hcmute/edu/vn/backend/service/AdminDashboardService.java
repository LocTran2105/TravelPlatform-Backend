package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.AdminDashboardResponse;
import hcmute.edu.vn.backend.dto.AdminDashboardResponse.ActivityDay;
import hcmute.edu.vn.backend.dto.AdminDashboardResponse.MapPoint;
import hcmute.edu.vn.backend.dto.AdminDashboardResponse.QueueItem;
import hcmute.edu.vn.backend.dto.AdminDashboardResponse.TypeCount;
import hcmute.edu.vn.backend.repository.PartnerApplicationRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.PartnerProfileRepository;
import hcmute.edu.vn.backend.repository.PartnerSubmissionRepository;
import hcmute.edu.vn.backend.repository.UserRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int ACTIVITY_DAYS = 14;
    private static final int QUEUE_SIZE = 6;
    private static final int MAP_LIMIT = 500;

    private final UserRepository userRepository;
    private final PartnerProfileRepository partnerProfileRepository;
    private final PartnerApplicationRepository applicationRepository;
    private final PartnerBusinessRepository businessRepository;
    private final PartnerSubmissionRepository submissionRepository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public AdminDashboardResponse getDashboard() {
        return new AdminDashboardResponse(
                userRepository.count(),
                userRepository.countByStatus("ACTIVE"),
                userRepository.countByStatus("LOCKED"),
                userRepository.countByRole("ROLE_TRAVELLER"),
                userRepository.countByRole("ROLE_PARTNER"),
                partnerProfileRepository.count(),
                applicationRepository.countByVerificationStatus("PENDING"),
                partnerProfileRepository.countByVerificationStatus("APPROVED"),
                businessRepository.countByDeletedAtIsNull(),
                businessRepository.countByVerificationStatusAndDeletedAtIsNull("PENDING_REVIEW"),
                submissionRepository.countByStatus("PENDING"),
                businessRepository.countByPublicationStatusAndDeletedAtIsNull("PUBLISHED"),
                countPendingReports(),
                activity(),
                queue(),
                businessTypeMix(),
                mapPoints()
        );
    }

    private long countPendingReports() {
        Object value = entityManager
                .createNativeQuery("SELECT COUNT(*) FROM reports WHERE status = 'PENDING'")
                .getSingleResult();
        return ((Number) value).longValue();
    }

    /** Hồ sơ nhận được và đã duyệt theo ngày, 14 ngày gần nhất (giờ Việt Nam). */
    private List<ActivityDay> activity() {
        LocalDate today = LocalDate.now(VN);
        LocalDate start = today.minusDays(ACTIVITY_DAYS - 1L);
        LocalDateTime from = start.atStartOfDay();

        Map<LocalDate, Long> submissions = countByDay(
                "SELECT DATE(COALESCE(submitted_at, created_at)), COUNT(*) FROM partner_submissions "
                        + "WHERE COALESCE(submitted_at, created_at) >= :from GROUP BY 1", from);
        Map<LocalDate, Long> applications = countByDay(
                "SELECT DATE(created_at), COUNT(*) FROM partner_applications WHERE created_at >= :from GROUP BY 1", from);
        Map<LocalDate, Long> approvals = countByDay(
                "SELECT DATE(reviewed_at), COUNT(*) FROM partner_submissions "
                        + "WHERE status = 'APPROVED' AND reviewed_at >= :from GROUP BY 1", from);
        countByDay("SELECT DATE(reviewed_at), COUNT(*) FROM partner_applications "
                + "WHERE verification_status = 'APPROVED' AND reviewed_at >= :from GROUP BY 1", from)
                .forEach((day, count) -> approvals.merge(day, count, Long::sum));

        List<ActivityDay> days = new ArrayList<>(ACTIVITY_DAYS);
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            days.add(new ActivityDay(
                    day.toString(),
                    submissions.getOrDefault(day, 0L),
                    applications.getOrDefault(day, 0L),
                    approvals.getOrDefault(day, 0L)));
        }
        return days;
    }

    private Map<LocalDate, Long> countByDay(String sql, LocalDateTime from) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(sql).setParameter("from", from).getResultList();
        Map<LocalDate, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            LocalDate day = toLocalDate(row[0]);
            if (day != null) result.put(day, ((Number) row[1]).longValue());
        }
        return result;
    }

    /** Việc chờ lâu nhất: yêu cầu duyệt địa điểm và đơn đăng ký đối tác, gộp rồi xếp theo thời gian gửi. */
    private List<QueueItem> queue() {
        List<QueueItem> items = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Object[]> submissions = entityManager.createNativeQuery(
                        "SELECT s.id, b.name, COALESCE(s.submitted_at, s.created_at), b.latitude, b.longitude "
                                + "FROM partner_submissions s JOIN partner_businesses b ON b.id = s.business_id "
                                + "WHERE s.status = 'PENDING' ORDER BY 3 ASC")
                .setMaxResults(QUEUE_SIZE)
                .getResultList();
        for (Object[] row : submissions) {
            items.add(new QueueItem("SUBMISSION", toLong(row[0]), (String) row[1], toIso(row[2]), toDouble(row[3]), toDouble(row[4])));
        }

        @SuppressWarnings("unchecked")
        List<Object[]> applications = entityManager.createNativeQuery(
                        "SELECT id, display_name, created_at FROM partner_applications "
                                + "WHERE verification_status = 'PENDING' ORDER BY created_at ASC")
                .setMaxResults(QUEUE_SIZE)
                .getResultList();
        for (Object[] row : applications) {
            items.add(new QueueItem("APPLICATION", toLong(row[0]), (String) row[1], toIso(row[2]), null, null));
        }

        items.sort(Comparator.comparing(QueueItem::createdAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return items.size() > QUEUE_SIZE ? new ArrayList<>(items.subList(0, QUEUE_SIZE)) : items;
    }

    private List<TypeCount> businessTypeMix() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT business_type, COUNT(*) FROM partner_businesses WHERE deleted_at IS NULL "
                                + "GROUP BY business_type ORDER BY 2 DESC")
                .getResultList();
        List<TypeCount> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new TypeCount((String) row[0], ((Number) row[1]).longValue()));
        }
        return result;
    }

    private List<MapPoint> mapPoints() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT id, name, latitude, longitude, "
                                + "CASE WHEN verification_status = 'PENDING_REVIEW' THEN 'PENDING' "
                                + "WHEN publication_status = 'PUBLISHED' THEN 'PUBLISHED' ELSE 'OTHER' END "
                                + "FROM partner_businesses "
                                + "WHERE deleted_at IS NULL AND latitude IS NOT NULL AND longitude IS NOT NULL "
                                + "ORDER BY id DESC")
                .setMaxResults(MAP_LIMIT)
                .getResultList();
        List<MapPoint> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new MapPoint(toLong(row[0]), (String) row[1], toDouble(row[2]), toDouble(row[3]), (String) row[4]));
        }
        return result;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        if (value instanceof LocalDate date) return date;
        if (value instanceof LocalDateTime dateTime) return dateTime.toLocalDate();
        return value == null ? null : LocalDate.parse(value.toString().substring(0, 10));
    }

    private static String toIso(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toString();
        if (value instanceof LocalDateTime dateTime) return dateTime.toString();
        return value == null ? null : value.toString().replace(' ', 'T');
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Double toDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
