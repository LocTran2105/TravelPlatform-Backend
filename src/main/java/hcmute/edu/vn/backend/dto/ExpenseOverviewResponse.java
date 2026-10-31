package hcmute.edu.vn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** Dữ liệu cho tab Ngân sách: danh sách chi phí + thành viên (để chọn người trả / chia tiền). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class ExpenseOverviewResponse {
    private Long currentUserId;
    private BigDecimal totalSpent;
    private List<MemberInfo> members;
    private List<ExpenseResponse> expenses;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class MemberInfo {
        private Long userId;
        private String fullName;
        private String avatar;
    }
}