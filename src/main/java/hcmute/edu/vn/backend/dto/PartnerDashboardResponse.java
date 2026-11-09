package hcmute.edu.vn.backend.dto;
import lombok.AllArgsConstructor; import lombok.Getter; import lombok.NoArgsConstructor; import lombok.Setter;
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PartnerDashboardResponse {
    private long totalBusinesses;
    private long businessesPendingReview;
    private long publishedBusinesses;
    private long totalMenuItems;
    private long activePromotions;
    private long pendingSubmissions;
}