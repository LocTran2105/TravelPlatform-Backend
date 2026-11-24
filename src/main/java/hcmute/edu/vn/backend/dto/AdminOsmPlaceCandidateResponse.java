package hcmute.edu.vn.backend.dto;

import java.math.BigDecimal;

/** OSM feature candidate returned to the Admin map for manual confirmation. */
public record AdminOsmPlaceCandidateResponse(
        Long id,
        String providerPlaceId,
        String name,
        String address,
        String placeType,
        BigDecimal latitude,
        BigDecimal longitude,
        Double distanceMeters
) { }
