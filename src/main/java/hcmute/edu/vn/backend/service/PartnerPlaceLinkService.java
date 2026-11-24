package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.repository.PartnerBusinessLinkRepository;
import hcmute.edu.vn.backend.repository.PlaceLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes Place <-> PartnerBusiness links only after an explicit Admin decision.
 * It intentionally contains no automatic name/distance matching.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PartnerPlaceLinkService {

    private static final double MAX_ADMIN_OSM_DISTANCE_METERS = 15_000.0;

    private final PartnerBusinessLinkRepository businessRepository;
    private final PlaceLinkRepository placeRepository;

    @Transactional
    public Place confirmOsmPlace(Long businessId, Long placeId) {
        PartnerBusiness business = requireBusiness(businessId);
        if (placeId == null || placeId <= 0) {
            throw new IllegalArgumentException("Admin chưa chọn OSM Place.");
        }

        Place selected = placeRepository.findById(placeId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy OSM Place được chọn."));

        if (!"OSM".equalsIgnoreCase(selected.getProvider())) {
            throw new IllegalArgumentException("Chỉ được liên kết với Place có provider = OSM.");
        }
        if (selected.getPartnerBusinessId() != null
                && !selected.getPartnerBusinessId().equals(businessId)) {
            throw new IllegalStateException("OSM Place này đã được liên kết với một Business khác.");
        }
        validateCoordinates(selected.getLatitude(), selected.getLongitude());

        if (business.getLatitude() != null && business.getLongitude() != null) {
            double distance = distanceMeters(
                    business.getLatitude(), business.getLongitude(),
                    selected.getLatitude(), selected.getLongitude());
            if (distance > MAX_ADMIN_OSM_DISTANCE_METERS) {
                throw new IllegalArgumentException(
                        "OSM Place cách vị trí Partner quá xa (" + Math.round(distance)
                                + "m). Hãy kiểm tra lại vị trí trên bản đồ trước khi xác nhận.");
            }
        }

        clearOtherMappings(businessId, selected.getId());
        selected.setPartnerBusinessId(businessId);
        Place saved = placeRepository.save(selected);
        log.info("Admin xác nhận Business {} -> OSM Place {} ({})", businessId,
                saved.getId(), saved.getProviderPlaceId());
        return saved;
    }

    @Transactional
    public Place createPartnerPlace(Long businessId, BigDecimal latitude, BigDecimal longitude) {
        PartnerBusiness business = requireBusiness(businessId);
        validateCoordinates(latitude, longitude);

        // The Admin-confirmed coordinates become the canonical coordinates for the Partner profile.
        business.setLatitude(latitude);
        business.setLongitude(longitude);
        businessRepository.save(business);

        String providerPlaceId = "partner-business/" + businessId;
        Place place = placeRepository.findByProviderAndProviderPlaceId("PARTNER", providerPlaceId)
                .orElseGet(Place::new);

        clearOtherMappings(businessId, place.getId());

        place.setProvider("PARTNER");
        place.setProviderPlaceId(providerPlaceId);
        place.setPartnerBusinessId(businessId);
        place.setName(business.getName());
        place.setAddress(business.getAddress());
        place.setDestination(destinationFor(business));
        place.setPlaceType(normalizeBusinessType(business.getBusinessType()));
        place.setLatitude(latitude);
        place.setLongitude(longitude);
        place.setCategories(ListHolder.partnerCategory(business.getBusinessType()));
        place.setFetchedAt(null); // This is Partner/Admin-confirmed data, not an OSM discovery result.
        if (place.getCurrency() == null || place.getCurrency().isBlank()) {
            place.setCurrency("VND");
        }

        Place saved = placeRepository.save(place);
        log.info("Admin tạo/xác nhận PARTNER Place {} cho Business {}", saved.getId(), businessId);
        return saved;
    }

    private PartnerBusiness requireBusiness(Long businessId) {
        if (businessId == null || businessId <= 0) {
            throw new IllegalArgumentException("Business ID không hợp lệ.");
        }
        return businessRepository.findById(businessId)
                .filter(business -> business.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Business hoặc Business đã bị xóa."));
    }

    /** Keep at most one canonical Place associated with a Partner Business. */
    private void clearOtherMappings(Long businessId, Long keepPlaceId) {
        for (String provider : List.of("OSM", "PARTNER")) {
            List<Place> current = placeRepository.findByPartnerBusinessIdAndProvider(businessId, provider);
            if (current == null) continue;
            for (Place existing : current) {
                if (existing == null || existing.getId() == null
                        || existing.getId().equals(keepPlaceId)) continue;
                existing.setPartnerBusinessId(null);
                placeRepository.save(existing);
            }
        }
    }

    private static String destinationFor(PartnerBusiness business) {
        String value = firstText(business.getCity(), business.getProvince(), business.getDistrict(), "Việt Nam");
        return value.length() > 100 ? value.substring(0, 100) : value;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "Việt Nam";
    }

    private static String normalizeBusinessType(String type) {
        String value = type == null ? "OTHER" : type.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "CAFE", "COFFEE_SHOP" -> "CAFE";
            case "RESTAURANT", "EATERY" -> "RESTAURANT";
            case "HOTEL", "HOMESTAY", "RESORT", "HOSTEL", "APARTMENT" -> "ACCOMMODATION";
            default -> "ATTRACTION";
        };
    }

    private static void validateCoordinates(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException("Latitude và longitude phải được xác nhận đầy đủ.");
        }
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new IllegalArgumentException("Latitude không hợp lệ.");
        }
        if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("Longitude không hợp lệ.");
        }
    }

    private static double distanceMeters(BigDecimal lat1, BigDecimal lon1,
                                         BigDecimal lat2, BigDecimal lon2) {
        double p1 = Math.toRadians(lat1.doubleValue());
        double p2 = Math.toRadians(lat2.doubleValue());
        double dLat = p2 - p1;
        double dLon = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Tiny helper avoids coupling category values to OSM tags. */
    private static final class ListHolder {
        private static List<String> partnerCategory(String type) {
            List<String> values = new ArrayList<>();
            values.add("partner_business_type=" + (type == null ? "OTHER" : type.trim().toUpperCase(Locale.ROOT)));
            return values;
        }
    }
}
