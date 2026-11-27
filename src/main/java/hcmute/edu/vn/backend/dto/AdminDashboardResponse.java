package hcmute.edu.vn.backend.dto;

import java.util.List;

public record AdminDashboardResponse(
        long totalUsers,
        long activeUsers,
        long lockedUsers,
        long totalTravellers,
        long totalPartners,
        long totalPartnerProfiles,
        long pendingPartnerApplications,
        long approvedPartnerProfiles,
        long totalBusinesses,
        long pendingBusinessReviews,
        long pendingSubmissions,
        long publishedBusinesses,
        long pendingReports,
        // 14 ngày gần nhất (cũ -> mới), đủ mọi ngày kể cả ngày không có hoạt động
        List<ActivityDay> activity,
        // Việc chờ lâu nhất (địa điểm + đơn đối tác), cũ nhất trước
        List<QueueItem> queue,
        List<TypeCount> businessTypeMix,
        // Địa điểm có tọa độ cho bản đồ mini: state = PENDING | PUBLISHED | OTHER
        List<MapPoint> mapPoints
) {
    public record ActivityDay(String date, long submissions, long applications, long approvals) {}

    /** type = SUBMISSION | APPLICATION; createdAt dạng ISO yyyy-MM-ddTHH:mm:ss */
    public record QueueItem(String type, Long id, String name, String createdAt, Double latitude, Double longitude) {}

    public record TypeCount(String type, long count) {}

    public record MapPoint(Long id, String name, Double latitude, Double longitude, String state) {}
}
