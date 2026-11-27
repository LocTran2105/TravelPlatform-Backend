package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PartnerDashboardResponse;
import hcmute.edu.vn.backend.dto.PartnerDashboardResponse.BusinessSummary;
import hcmute.edu.vn.backend.dto.PartnerDashboardResponse.RecentSubmission;
import hcmute.edu.vn.backend.entity.PartnerProfile;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.PartnerMenuItemRepository;
import hcmute.edu.vn.backend.repository.PartnerPromotionRepository;
import hcmute.edu.vn.backend.repository.PartnerSubmissionRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PartnerDashboardService {

    private static final int RECENT_SUBMISSIONS = 6;

    private final PartnerAccessService accessService;
    private final PartnerBusinessRepository businessRepository;
    private final PartnerMenuItemRepository menuItemRepository;
    private final PartnerPromotionRepository promotionRepository;
    private final PartnerSubmissionRepository submissionRepository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public PartnerDashboardResponse getDashboard(String email) {
        PartnerProfile partner = accessService.requireApprovedPartner(email);
        Long partnerId = partner.getId();
        List<RecentSubmission> submissions = submissions(partnerId);

        return new PartnerDashboardResponse(
                businessRepository.countByPartner_IdAndDeletedAtIsNull(partnerId),
                businessRepository.countByPartner_IdAndVerificationStatusAndDeletedAtIsNull(partnerId, "PENDING_REVIEW"),
                businessRepository.countByPartner_IdAndPublicationStatusAndDeletedAtIsNull(partnerId, "PUBLISHED"),
                menuItemRepository.countByCategory_Business_Partner_Id(partnerId),
                promotionRepository.countByBusiness_Partner_IdAndStatus(partnerId, "ACTIVE"),
                submissionRepository.countByBusiness_Partner_IdAndStatus(partnerId, "PENDING"),
                businesses(partnerId, submissions),
                submissions.size() > RECENT_SUBMISSIONS ? new ArrayList<>(submissions.subList(0, RECENT_SUBMISSIONS)) : submissions
        );
    }

    /** Tất cả lần gửi duyệt của đối tác, mới nhất trước (dùng cho "lần gửi gần nhất" của từng quán). */
    private List<RecentSubmission> submissions(Long partnerId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT s.id, s.business_id, b.name, s.status, COALESCE(s.reviewed_at, s.submitted_at, s.created_at), s.review_note "
                                + "FROM partner_submissions s JOIN partner_businesses b ON b.id = s.business_id "
                                + "WHERE b.partner_id = :partnerId ORDER BY s.id DESC")
                .setParameter("partnerId", partnerId)
                .setMaxResults(200)
                .getResultList();
        List<RecentSubmission> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new RecentSubmission(toLong(row[0]), toLong(row[1]), (String) row[2], (String) row[3], toIso(row[4]), (String) row[5]));
        }
        return result;
    }

    private List<BusinessSummary> businesses(Long partnerId, List<RecentSubmission> submissions) {
        Map<Long, RecentSubmission> latest = new HashMap<>();
        for (RecentSubmission s : submissions) latest.putIfAbsent(s.businessId(), s);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT b.id, b.name, b.business_type, b.address, b.verification_status, b.publication_status, b.operating_status, "
                                + "(SELECT COALESCE(m.thumbnail_url, m.media_url) FROM partner_business_media m "
                                + "  WHERE m.business_id = b.id AND COALESCE(m.moderation_status, '') <> 'REJECTED' "
                                + "  ORDER BY m.is_cover DESC, m.sort_order ASC, m.id ASC LIMIT 1), "
                                + "(SELECT COUNT(DISTINCT h.weekday) FROM partner_business_hours h WHERE h.business_id = b.id), "
                                + "(SELECT COUNT(*) FROM partner_business_media m WHERE m.business_id = b.id "
                                + "  AND COALESCE(m.moderation_status, '') <> 'REJECTED'), "
                                + "(SELECT COUNT(*) FROM partner_business_notes n WHERE n.business_id = b.id AND n.is_published = 1), "
                                + "(COALESCE(b.phone_number, '') <> '' OR COALESCE(b.email, '') <> ''), "
                                + "(COALESCE(b.description, '') <> '' OR COALESCE(b.short_description, '') <> '') "
                                + "FROM partner_businesses b WHERE b.partner_id = :partnerId AND b.deleted_at IS NULL "
                                + "ORDER BY b.updated_at DESC, b.id DESC")
                .setParameter("partnerId", partnerId)
                .getResultList();

        List<BusinessSummary> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Long id = toLong(row[0]);
            RecentSubmission last = latest.get(id);
            result.add(new BusinessSummary(
                    id, (String) row[1], (String) row[2], (String) row[3],
                    (String) row[4], (String) row[5], (String) row[6], (String) row[7],
                    toInt(row[8]), toInt(row[9]), toInt(row[10]), toBool(row[11]), toBool(row[12]),
                    last == null ? null : last.status(),
                    last == null ? null : last.at(),
                    last == null ? null : last.reviewNote()));
        }
        return result;
    }

    private static String toIso(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toString();
        if (value instanceof LocalDateTime dateTime) return dateTime.toString();
        return value == null ? null : value.toString().replace(' ', 'T');
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static int toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean b) return b;
        return value instanceof Number n && n.intValue() != 0;
    }
}
