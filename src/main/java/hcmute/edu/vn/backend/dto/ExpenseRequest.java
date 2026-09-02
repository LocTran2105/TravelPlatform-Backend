package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class ExpenseRequest {
    private String title; // Ví dụ: "Tiền phòng KS 2 đêm"
    private BigDecimal amount; // Tổng số tiền đã trả

    // Danh sách ID của những người có tham gia và phải chịu khoản tiền này
    private List<Long> splitUserIds;
}