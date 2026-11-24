package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.PartnerBusinessMedia;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.repository.PlaceLinkRepository;
import hcmute.edu.vn.backend.repository.PublicPartnerBusinessMediaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PlaceCoverService {

    private static final int MAX_PLACE_IDS = 100;
    private static final Set<String> PARTNER_ENRICHED_PROVIDERS = Set.of("OSM", "PARTNER");

    private final PlaceLinkRepository placeRepository;
    private final PublicPartnerBusinessMediaRepository mediaRepository;

    /** Returns { placeId: coverUrl } for an explicitly linked, publicly approved Partner Business. */
    @Transactional(readOnly = true)
    public Map<Long, String> getCoverUrls(Collection<Long> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) return Map.of();

        Set<Long> placeIds = new LinkedHashSet<>();
        for (Long id : requestedIds) {
            if (id != null && id > 0) placeIds.add(id);
            if (placeIds.size() >= MAX_PLACE_IDS) break;
        }
        if (placeIds.isEmpty()) return Map.of();

        List<Place> places = placeRepository.findAllById(placeIds);
        Map<Long, Long> placeToBusiness = new LinkedHashMap<>();
        Set<Long> businessIds = new LinkedHashSet<>();
        for (Place place : places) {
            if (place == null || place.getId() == null || place.getPartnerBusinessId() == null) continue;
            String provider = place.getProvider() == null ? "" : place.getProvider().toUpperCase();
            if (!PARTNER_ENRICHED_PROVIDERS.contains(provider)) continue;
            placeToBusiness.put(place.getId(), place.getPartnerBusinessId());
            businessIds.add(place.getPartnerBusinessId());
        }
        if (businessIds.isEmpty()) return Map.of();

        List<PartnerBusinessMedia> media = mediaRepository.findPublicImagesForBusinesses(businessIds);
        Map<Long, String> businessToCover = new LinkedHashMap<>();
        for (PartnerBusinessMedia item : media) {
            if (item == null || item.getBusiness() == null || item.getBusiness().getId() == null) continue;
            String url = hasText(item.getThumbnailUrl()) ? item.getThumbnailUrl() : item.getMediaUrl();
            if (hasText(url)) businessToCover.putIfAbsent(item.getBusiness().getId(), url.trim());
        }

        Map<Long, String> result = new LinkedHashMap<>();
        placeToBusiness.forEach((placeId, businessId) -> {
            String url = businessToCover.get(businessId);
            if (hasText(url)) result.put(placeId, url);
        });
        return result;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
