package hcmute.edu.vn.backend.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * So khớp tên địa điểm "gần giống" để gộp kết quả trùng: bỏ dấu, chữ thường, bỏ các từ chung
 * như "cà phê", "coffee", "quán", "nhà hàng"... ("Cafe Tùng" ~ "Tùng Coffee").
 */
public final class PlaceNameMatcher {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]+");
    private static final Pattern GENERIC_WORDS = Pattern.compile(
            "\\b(tiem|quan an|quan|ca phe|caphe|cafe|coffee|coffe|nha hang|restaurant|khach san|hotel"
                    + "|homestay|resort|the|da lat|dalat)\\b");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private PlaceNameMatcher() { }

    public static String normalize(String name) {
        if (name == null) return "";
        String s = Normalizer.normalize(name, Normalizer.Form.NFD);
        s = MARKS.matcher(s).replaceAll("").replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
        s = NON_ALNUM.matcher(s).replaceAll(" ");
        s = GENERIC_WORDS.matcher(s).replaceAll(" ");
        return SPACES.matcher(s).replaceAll(" ").trim();
    }

    /** Tên chuẩn hóa trùng nhau, hoặc tên này chứa tên kia và phần ngắn dài ít nhất một nửa phần dài. */
    public static boolean similar(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.length() < 3 || y.length() < 3) return false;
        String shorter = x.length() <= y.length() ? x : y;
        String longer = x.length() <= y.length() ? y : x;
        return longer.contains(shorter) && shorter.length() * 2 >= longer.length();
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6_371_000.0 * Math.asin(Math.sqrt(a));
    }
}
