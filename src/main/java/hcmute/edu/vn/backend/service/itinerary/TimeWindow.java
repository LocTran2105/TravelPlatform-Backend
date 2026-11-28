package hcmute.edu.vn.backend.service.itinerary;

import java.time.LocalTime;
import java.util.Locale;

/** Khung giờ trong ngày, tính bằng phút từ 00:00 (end có thể = 1440 khi mở tới nửa đêm). */
public record TimeWindow(int start, int end) {

    public TimeWindow {
        if (end < start) throw new IllegalArgumentException("end < start");
    }

    public static TimeWindow of(String start, String end) {
        return new TimeWindow(parse(start), parse(end));
    }

    public boolean contains(int minute) {
        return minute >= start && minute <= end;
    }

    /** Số phút từ {@code minute} tới khung này (0 nếu nằm trong khung). */
    public int distanceTo(int minute) {
        if (minute < start) return start - minute;
        if (minute > end) return minute - end;
        return 0;
    }

    public String label() {
        return format(start) + "–" + format(end);
    }

    public static int parse(String hhmm) {
        String[] parts = hhmm.trim().split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    public static int of(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    /** 0..1439 -> "HH:mm"; >= 1440 hiển thị dạng "24:10" để thấy rõ là quá nửa đêm. */
    public static String format(int minute) {
        int m = Math.max(0, minute);
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }
}
