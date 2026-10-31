package hcmute.edu.vn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** Kết quả "Cân đối nhóm": số dư từng người + danh sách chuyển khoản tối giản. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class SettlementResponse {
    private List<BalanceInfo> balances;
    private List<TransferInfo> transfers;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class BalanceInfo {
        private Long userId;
        private String fullName;
        private String avatar;
        private BigDecimal balance; // > 0: sẽ được nhận lại, < 0: còn nợ
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class TransferInfo {
        private Long fromUserId;
        private String fromName;
        private Long toUserId;
        private String toName;
        private BigDecimal amount;
    }
}