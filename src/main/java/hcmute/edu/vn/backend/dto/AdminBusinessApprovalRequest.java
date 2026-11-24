package hcmute.edu.vn.backend.dto;

import java.math.BigDecimal;

/** Admin must explicitly choose an OSM Place or confirm a Partner-owned location. */
public record AdminBusinessApprovalRequest(
        String note,
        String placeProvider,
        Long placeId,
        BigDecimal latitude,
        BigDecimal longitude
) { }
