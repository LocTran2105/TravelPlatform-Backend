package hcmute.edu.vn.backend.service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Tạo username từ email, luôn khớp CHECK của bảng users:
 *   ^[a-z0-9][a-z0-9._-]{1,8}[a-z0-9]$   (3-10 ký tự, đầu và cuối là chữ/số)
 *
 * Ví dụ: tranhuuloc05@gmail.com -> tranhuuloc; trùng thì tranhuulo1, tranhuulo2...
 */
final class UsernameGenerator {

    static final int MIN_LENGTH = 3;
    static final int MAX_LENGTH = 10;
    private static final int MAX_COUNTER = 99;
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private UsernameGenerator() {}

    /** Phần trước @, chữ thường, chỉ giữ a-z 0-9 . _ - ; luôn trả về chuỗi hợp lệ 3-10 ký tự. */
    static String baseFromEmail(String email) {
        String local = email == null ? "" : email;
        int at = local.indexOf('@');
        if (at >= 0) local = local.substring(0, at);

        String base = trimEdges(local.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", ""));
        if (base.length() > MAX_LENGTH) {
            base = trimEdges(base.substring(0, MAX_LENGTH));
        }
        if (base.isEmpty()) {
            base = "user";
        }
        if (base.length() < MIN_LENGTH) {
            // Thiếu ký tự thì nối số (giống cách làm cũ: "ab" -> "ab123")
            base = (base + "123").substring(0, Math.min(MAX_LENGTH, base.length() + 3));
        }
        return base;
    }

    /** base + số đếm, tự cắt bớt base cho vừa 10 ký tự. */
    static String withSuffix(String base, int n) {
        String suffix = String.valueOf(n);
        int keep = Math.max(1, MAX_LENGTH - suffix.length());
        String head = base.length() > keep ? base.substring(0, keep) : base;
        head = trimEdges(head);
        if (head.isEmpty()) head = "u";
        String candidate = head + suffix;
        while (candidate.length() < MIN_LENGTH) candidate = candidate + "0";
        return candidate;
    }

    /** Thử base, rồi base1..base99, cuối cùng là hậu tố ngẫu nhiên. Trả null nếu vẫn không tìm được. */
    static String unique(String email, Predicate<String> exists) {
        String base = baseFromEmail(email);
        if (!exists.test(base)) return base;

        for (int i = 1; i <= MAX_COUNTER; i++) {
            String candidate = withSuffix(base, i);
            if (!exists.test(candidate)) return candidate;
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
            String head = trimEdges(base.substring(0, Math.min(base.length(), MAX_LENGTH - 4)));
            String candidate = (head.isEmpty() ? "u" : head) + sb;
            if (!exists.test(candidate)) return candidate;
        }
        return null;
    }

    static boolean isValid(String username) {
        return username != null && username.matches("^[a-z0-9][a-z0-9._-]{1,8}[a-z0-9]$");
    }

    /** Bỏ . _ - ở đầu và cuối (DB bắt đầu và kết thúc phải là chữ hoặc số). */
    private static String trimEdges(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && !isAlnum(s.charAt(start))) start++;
        while (end > start && !isAlnum(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
