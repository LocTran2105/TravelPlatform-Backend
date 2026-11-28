package hcmute.edu.vn.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** DTO của mục "Nơi ở" trong chuyến đi. */
public final class TripStayDtos {

    private TripStayDtos() {}

    /** Body của POST/PUT /api/trips/{tripId}/stays. placeId có khi người dùng chọn từ kết quả tìm kiếm trong DB. */
    public record StayRequest(
            Long placeId,
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            String note) {}

    public record StayResponse(
            Long id,
            Long tripId,
            Long placeId,
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            int nights,
            String note) {}
}
