package hcmute.edu.vn.backend.service.itinerary;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Suy ra mục đích ghé thăm (săn mây, ngắm hoàng hôn, cà phê chụp ảnh, ăn trưa...) từ loại địa điểm,
 * thẻ OSM, tên và ghi chú của người dùng, rồi đặt khung giờ đẹp nhất theo giờ mặt trời mọc/lặn của ngày đó.
 *
 * <p>Ghi chú của người dùng được ưu tiên: ghi "săn mây" ở một quán cà phê trên đồi thì quán đó được xếp lúc bình minh.</p>
 */
public final class VisitProfileResolver {

    /** Thông tin cần để suy ra mục đích của một điểm. */
    public record PlaceFacts(String name, String placeType, List<String> categories, String note, List<String> tripTags) { }

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9=]+");

    private static final List<TimeWindow> ALL_DAY = List.of(new TimeWindow(0, 24 * 60));
    private static final List<TimeWindow> CAFE_HOURS = List.of(TimeWindow.of("07:00", "22:00"));
    private static final List<TimeWindow> RESTAURANT_HOURS = List.of(TimeWindow.of("10:00", "21:30"));
    private static final List<TimeWindow> BREAKFAST_HOURS = List.of(TimeWindow.of("06:00", "11:00"));
    private static final List<TimeWindow> SNACK_HOURS = List.of(TimeWindow.of("13:00", "22:30"));
    private static final List<TimeWindow> NIGHT_HOURS = List.of(TimeWindow.of("17:00", "23:00"));
    private static final List<TimeWindow> ATTRACTION_HOURS = List.of(TimeWindow.of("07:00", "17:30"));
    private static final List<TimeWindow> MUSEUM_HOURS = List.of(TimeWindow.of("07:30", "17:00"));
    private static final List<TimeWindow> MARKET_HOURS = List.of(TimeWindow.of("06:00", "18:00"));

    private VisitProfileResolver() { }

    /**
     * @param intensity RELAX / BALANCED / PACKED: nhân thời gian ở lại ×1,25 / ×1 / ×0,8
     */
    public static VisitProfile resolve(PlaceFacts facts, LocalDate date, double latitude, double longitude, String intensity) {
        VisitProfile base = resolveBase(facts, date, latitude, longitude);
        double factor = switch (intensity == null ? "" : intensity.toUpperCase(Locale.ROOT)) {
            case "RELAX" -> 1.25;
            case "PACKED" -> 0.8;
            default -> 1.0;
        };
        int dwell = (int) Math.round(base.dwellMinutes() * factor / 5.0) * 5;
        return base.withDwell(Math.max(20, dwell));
    }

    private static VisitProfile resolveBase(PlaceFacts facts, LocalDate date, double latitude, double longitude) {
        String name = normalize(facts.name());
        String note = normalize(facts.note());
        String text = " " + name + " " + note + " ";
        String tags = facts.categories() == null ? "" : " " + normalize(String.join(" ", facts.categories())) + " ";
        String type = facts.placeType() == null ? "" : facts.placeType().toUpperCase(Locale.ROOT);
        String tripTags = facts.tripTags() == null ? "" : " " + normalize(String.join(" ", facts.tripTags())) + " ";

        SunCalculator.SunTimes sun = SunCalculator.compute(date, latitude, longitude);
        int sunrise = sun.sunrise();
        int sunset = sun.sunset();
        boolean rainySeason = date.getMonthValue() >= 5 && date.getMonthValue() <= 10;


        // 1. Mục đích gắn với mặt trời: ưu tiên cao nhất vì lệch giờ là mất trải nghiệm.
        if (has(text, "san may", "dinh may", "cau dat", "da phu", "hon bo", "doi may", "bien may")) {
            return new VisitProfile("CLOUD_HUNT", "Săn mây", 90,
                    List.of(new TimeWindow(sunrise - 45, sunrise + 75)), 6, true,
                    "Săn mây đẹp nhất lúc bình minh (mặt trời mọc " + TimeWindow.format(sunrise) + ")", ALL_DAY);
        }
        if (has(text, "binh minh", "sunrise", "mat troi moc", "ngam ban mai")) {
            return new VisitProfile("SUNRISE", "Ngắm bình minh", 45,
                    List.of(new TimeWindow(sunrise - 30, sunrise + 45)), 6, true,
                    "Đón bình minh (mặt trời mọc " + TimeWindow.format(sunrise) + ")", ALL_DAY);
        }
        if (has(text, "hoang hon", "sunset", "mat troi lan", "chieu ta")) {
            return new VisitProfile("SUNSET", "Ngắm hoàng hôn", 60,
                    List.of(new TimeWindow(sunset - 75, sunset - 10)), 6, true,
                    "Ngắm hoàng hôn (mặt trời lặn " + TimeWindow.format(sunset) + ")", ALL_DAY);
        }
        if (has(text, "cho dem", "night market", "pho di bo", "pho dem", "bar", "pub", "beer", "bia")) {
            return new VisitProfile("NIGHT", "Đi chơi đêm", 90,
                    List.of(TimeWindow.of("18:00", "21:30")), 3, true,
                    "Chợ đêm/phố đêm đông vui sau 18:00", NIGHT_HOURS);
        }

        if (type.equals("ACCOMMODATION") || type.equals("HOTEL") || type.equals("HOMESTAY")) {
            return new VisitProfile("STAY", "Nơi ở", 30, List.of(), 0, false, "Nơi ở", ALL_DAY);
        }

        // 2. Ăn uống: xếp đúng bữa.
        boolean restaurant = type.equals("RESTAURANT") || type.equals("FOOD") || tags.contains("amenity=restaurant")
                || tags.contains("amenity=fast_food") || has(text, "nha hang", "quan an", "buffet", "lau ", "nuong", "com ");
        if (has(text, "banh can", "pho ", "bun ", "banh mi", "xoi", "chao", "hu tieu", "com tam", "an sang",
                "sua dau nanh", "banh uot")) {
            return new VisitProfile("BREAKFAST", "Ăn sáng", 45,
                    List.of(TimeWindow.of("06:30", "09:00")), 3, true,
                    "Món ăn sáng, nên đi đầu ngày", BREAKFAST_HOURS);
        }
        if (has(text, "banh trang nuong", "kem ", "che ", "sua chua", "an vat", "oc ")) {
            return new VisitProfile("SNACK", "Ăn vặt", 40,
                    List.of(TimeWindow.of("14:30", "21:00")), 1, false,
                    "Ăn vặt hợp buổi chiều tối", SNACK_HOURS);
        }
        if (restaurant) {
            boolean dinnerFirst = has(text, "lau", "nuong", "buffet");
            List<TimeWindow> meals = List.of(TimeWindow.of("11:00", "13:00"), TimeWindow.of("17:30", "20:00"));
            return new VisitProfile("MEAL", "Bữa chính", 75, meals, 3, true,
                    dinnerFirst ? "Đúng giờ ăn (lẩu/nướng hợp bữa tối se lạnh)" : "Đúng giờ ăn trưa/tối", RESTAURANT_HOURS);
        }

        // 3. Cà phê: chụp ảnh thì canh giờ vàng.
        boolean cafe = type.equals("CAFE") || tags.contains("amenity=cafe") || has(text, "cafe", "ca phe", "coffee");
        boolean photo = has(text, "check in", "checkin", "chup anh", "song ao", "view", "doi ", "rung thong", "vuon")
                || has(tripTags, "chup anh", "check in", "song ao", "photo");
        if (cafe && photo) {
            return new VisitProfile("PHOTO_CAFE", "Cà phê chụp ảnh", 60,
                    List.of(new TimeWindow(sunrise + 30, TimeWindow.parse("09:30")),
                            new TimeWindow(sunset - 120, sunset - 30)), 2, false,
                    "Ánh sáng giờ vàng để chụp ảnh đẹp", CAFE_HOURS);
        }
        if (cafe) {
            return new VisitProfile("CAFE", "Cà phê", 60,
                    List.of(TimeWindow.of("07:00", "11:00"), TimeWindow.of("14:00", "17:30")), 0.5, false,
                    "Giờ cà phê dễ chịu", CAFE_HOURS);
        }

        // 4. Điểm tham quan.
        if (tags.contains("tourism=viewpoint") || has(text, "view point", "viewpoint", "dinh nui", "deo ")) {
            return new VisitProfile("VIEWPOINT", "Ngắm cảnh", 45,
                    List.of(new TimeWindow(sunrise + 15, TimeWindow.parse("09:30")),
                            new TimeWindow(sunset - 90, sunset - 10)), 2, false,
                    "Ánh sáng đẹp nhất buổi sáng sớm hoặc chiều muộn", ALL_DAY);
        }
        if (tags.contains("tourism=museum") || tags.contains("tourism=gallery") || has(text, "bao tang", "museum",
                "trien lam", "nha tho", "chua ", "thien vien", "dinh ", "biet thu", "ga ", "crazy house")) {
            return new VisitProfile("INDOOR", "Tham quan", 75,
                    List.of(TimeWindow.of("08:00", "16:00")), 0.5, false,
                    "Tham quan trong giờ mở cửa", MUSEUM_HOURS);
        }
        if (has(" " + name + " ", "cho", "market")) {
            return new VisitProfile("MARKET", "Đi chợ", 60,
                    List.of(TimeWindow.of("07:00", "11:00")), 1, false,
                    "Chợ đông vui, hàng tươi vào buổi sáng", MARKET_HOURS);
        }
        boolean bigPark = has(text, "kdl", "khu du lich", "langbiang", "lang biang", "thac", "thung lung", "cong vien");
        if (bigPark || tags.contains("natural=") || tags.contains("leisure=park") || tags.contains("tourism=theme_park")
                || has(text, "ho ", "rung", "doi che", "vuon")) {
            List<TimeWindow> windows = new ArrayList<>();
            windows.add(TimeWindow.of("07:30", "11:30"));
            if (!rainySeason) windows.add(TimeWindow.of("14:00", "16:30"));
            String reason = rainySeason
                    ? "Đi buổi sáng: mùa mưa (tháng 5–10) chiều thường có mưa"
                    : "Ngoài trời, đi lúc mát và còn nắng đẹp";
            return new VisitProfile("OUTDOOR", "Ngoài trời", bigPark ? 150 : 90, windows, rainySeason ? 2.5 : 1.5,
                    false, reason, ATTRACTION_HOURS);
        }
        return new VisitProfile("ATTRACTION", "Tham quan", 90,
                List.of(TimeWindow.of("08:00", "17:00")), 0.5, false, "Tham quan ban ngày", ATTRACTION_HOURS);
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        String s = Normalizer.normalize(value, Normalizer.Form.NFD);
        s = MARKS.matcher(s).replaceAll("").replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
        return NON_ALNUM.matcher(s).replaceAll(" ").trim();
    }

    /** Có chứa một cụm từ khóa (so khớp theo từ: " ho " không khớp "homestay"). */
    private static boolean has(String text, String... keywords) {
        for (String keyword : keywords) {
            String k = keyword.trim();
            if (k.isEmpty()) continue;
            if (text.contains(" " + k + " ") || text.contains(" " + k + "=")) return true;
        }
        return false;
    }
}
