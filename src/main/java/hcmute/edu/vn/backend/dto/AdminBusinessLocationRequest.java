package hcmute.edu.vn.backend.dto;

import java.math.BigDecimal;

public record AdminBusinessLocationRequest(
        String address,
        String ward,
        String district,
        String city,
        String province,
        BigDecimal latitude,
        BigDecimal longitude
) {
}