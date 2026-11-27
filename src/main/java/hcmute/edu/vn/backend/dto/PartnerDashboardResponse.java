package hcmute.edu.vn.backend.dto;
import lombok.AllArgsConstructor; import lombok.Getter; import lombok.NoArgsConstructor; import lombok.Setter;
import java.util.List;
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PartnerDashboardResponse {
    private long totalBusinesses;
    private long businessesPendingReview;
    private long publishedBusinesses;
    private long totalMenuItems;
    private long activePromotions;
    private long pendingSubmissions;
    // Từng địa điểm của đối tác kèm mức độ hoàn thiện hồ sơ
    private List<BusinessSummary> businesses;
    // 6 lần gửi duyệt gần nhất
    private List<RecentSubmission> recentSubmissions;

    /**
     * hoursDays: số ngày trong tuần đã khai giờ; mediaCount: ảnh không bị từ chối;
     * notesCount: ghi chú đang hiển thị; hasContact: có số điện thoại hoặc email; hasDescription: có mô tả.
     */
    public record BusinessSummary(
            Long id, String name, String businessType, String address,
            String verificationStatus, String publicationStatus, String operatingStatus, String coverUrl,
            int hoursDays, int mediaCount, int notesCount, boolean hasContact, boolean hasDescription,
            String lastSubmissionStatus, String lastSubmissionAt, String lastReviewNote) {}

    public record RecentSubmission(Long id, Long businessId, String businessName, String status, String at, String reviewNote) {}
}
