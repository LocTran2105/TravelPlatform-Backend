package hcmute.edu.vn.backend.service.itinerary;

import java.time.LocalDate;

/**
 * Giờ mặt trời mọc/lặn theo công thức của NOAA (độ chính xác khoảng ±2 phút ở vĩ độ Việt Nam),
 * dùng để đặt khung giờ săn mây, bình minh, hoàng hôn và giờ vàng chụp ảnh.
 */
public final class SunCalculator {

    /** Việt Nam dùng UTC+7, không đổi giờ theo mùa. */
    public static final int VIETNAM_UTC_OFFSET_MINUTES = 7 * 60;
    private static final double ZENITH_DEGREES = 90.833; // tính cả khúc xạ khí quyển và bán kính mặt trời

    /** Giờ địa phương tính bằng phút từ 00:00. */
    public record SunTimes(int sunrise, int sunset) { }

    private SunCalculator() { }

    public static SunTimes compute(LocalDate date, double latitude, double longitude) {
        return compute(date, latitude, longitude, VIETNAM_UTC_OFFSET_MINUTES);
    }

    public static SunTimes compute(LocalDate date, double latitude, double longitude, int utcOffsetMinutes) {
        int dayOfYear = date.getDayOfYear();
        double daysInYear = date.isLeapYear() ? 366 : 365;
        double gamma = 2 * Math.PI / daysInYear * (dayOfYear - 1);

        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma)
                - 0.014615 * Math.cos(2 * gamma) - 0.040849 * Math.sin(2 * gamma));
        double decl = 0.006918 - 0.399912 * Math.cos(gamma) + 0.070257 * Math.sin(gamma)
                - 0.006758 * Math.cos(2 * gamma) + 0.000907 * Math.sin(2 * gamma)
                - 0.002697 * Math.cos(3 * gamma) + 0.00148 * Math.sin(3 * gamma);

        double latRad = Math.toRadians(latitude);
        double cosHa = Math.cos(Math.toRadians(ZENITH_DEGREES)) / (Math.cos(latRad) * Math.cos(decl))
                - Math.tan(latRad) * Math.tan(decl);
        cosHa = Math.max(-1, Math.min(1, cosHa)); // vùng cực: không mọc/lặn
        double haDegrees = Math.toDegrees(Math.acos(cosHa));

        double sunriseUtc = 720 - 4 * (longitude + haDegrees) - eqTime;
        double sunsetUtc = 720 - 4 * (longitude - haDegrees) - eqTime;
        return new SunTimes(
                (int) Math.round(sunriseUtc + utcOffsetMinutes),
                (int) Math.round(sunsetUtc + utcOffsetMinutes));
    }
}
