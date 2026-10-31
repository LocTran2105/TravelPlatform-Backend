package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class ExpenseRequest {
    private String title;          // Tên hiển thị (nếu trống: lấy tên địa điểm, hoặc tên danh mục)
    private BigDecimal amount;     // Tổng số tiền đã chi (VNĐ, làm tròn đến đồng)
    private String category;       // FLIGHTS, LODGING, ... (trống = OTHER)
    private String description;    // Mô tả thêm (không bắt buộc)
    private Long tripDestinationId; // Địa điểm trong lịch trình (không bắt buộc)
    private Long payerId;          // Người trả tiền (trống = người đang đăng nhập)
    private LocalDate expenseDate; // Ngày chi (trống = hôm nay)

    // Những người cùng chịu khoản chi này (chia đều).
    // Để trống hoặc null = "Không chia": người trả tự chịu toàn bộ.
    private List<Long> splitUserIds;
}