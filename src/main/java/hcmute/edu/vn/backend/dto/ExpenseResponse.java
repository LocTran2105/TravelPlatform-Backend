package hcmute.edu.vn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class ExpenseResponse {
    private Long id;
    private String title;
    private String category;
    private String description;
    private BigDecimal amount;
    private LocalDate expenseDate;
    private Long tripDestinationId;
    private Long payerId;
    private String payerName;
    private String payerAvatar;
    private List<SplitInfo> splits;
    private LocalDateTime createdAt;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class SplitInfo {
        private Long userId;
        private String userName;
        private BigDecimal amountOwed;
    }
}