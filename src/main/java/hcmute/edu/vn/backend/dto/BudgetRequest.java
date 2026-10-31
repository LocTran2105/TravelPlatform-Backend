package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter
@Setter
public class BudgetRequest {
    private BigDecimal totalBudget;
}