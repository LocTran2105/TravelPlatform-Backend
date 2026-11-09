package hcmute.edu.vn.backend.dto;

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
        long publishedBusinesses
) {
}