package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Thông tin rút gọn của chuyến đi để hiển thị ở trang cá nhân của người khác.
 * Cố ý KHÔNG có ngân sách, danh sách thành viên, email... để không lộ dữ liệu
 * riêng tư (trả nguyên entity Trip sẽ kéo theo cả những thứ đó).
 */
@Getter
@Setter
public class TripSummaryResponse {
    private Long id;
    private String title;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private String visibility; // PUBLIC, FOLLOWERS, FRIENDS, PRIVATE
    private Integer memberCount;
}