package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PartnerPlaceProfileResponse;
import hcmute.edu.vn.backend.dto.PartnerPlaceProfileResponse.HoursSlot;
import hcmute.edu.vn.backend.dto.PartnerPlaceProfileResponse.Media;
import hcmute.edu.vn.backend.dto.PartnerPlaceProfileResponse.Note;
import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.PartnerBusinessHours;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.repository.PartnerBusinessHoursRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessNoteRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.PlaceLinkRepository;
import hcmute.edu.vn.backend.repository.PublicPartnerBusinessMediaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dữ liệu Partner công khai cho du khách: chỉ trả khi Place được liên kết rõ ràng với một Business
 * đã duyệt, đang công khai và chưa xóa (cùng điều kiện với ảnh cover trong {@link PlaceCoverService}).
 */
@Service
@RequiredArgsConstructor
public class PartnerPlaceProfileService {

    private static final int MAX_PLACE_IDS = 100;
    private static final Set<String> PARTNER_ENRICHED_PROVIDERS = Set.of("OSM", "PARTNER");
    private static final Set<String> APPROVED_STATUSES = Set.of("APPROVED", "VERIFIED");
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final PlaceLinkRepository placeRepository;
    private final PartnerBusinessRepository businessRepository;
    private final PartnerBusinessHoursRepository hoursRepository;
    private final PartnerBusinessNoteRepository noteRepository;
    private final PublicPartnerBusinessMediaRepository mediaRepository;

    @Transactional(readOnly = true)
    public Optional<PartnerPlaceProfileResponse> getProfile(Long placeId) {
        if (placeId == null || placeId <= 0) return Optional.empty();
        return placeRepository.findById(placeId)
                .flatMap(this::publicBusinessOf)
                .map(business -> toProfile(placeId, business));
    }

    /** { placeId: giờ mở cửa } cho các Place có Partner công khai; Place khác không có trong kết quả. */
    @Transactional(readOnly = true)
    public Map<Long, List<HoursSlot>> getHours(Collection<Long> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) return Map.of();
        Set<Long> placeIds = new LinkedHashSet<>();
        for (Long id : requestedIds) {
            if (id != null && id > 0) placeIds.add(id);
            if (placeIds.size() >= MAX_PLACE_IDS) break;
        }
        Map<Long, List<HoursSlot>> result = new LinkedHashMap<>();
        for (Place place : placeRepository.findAllById(placeIds)) {
            publicBusinessOf(place).ifPresent(business -> {
                List<HoursSlot> hours = hoursOf(business.getId());
                if (!hours.isEmpty()) result.put(place.getId(), hours);
            });
        }
        return result;
    }

    private Optional<PartnerBusiness> publicBusinessOf(Place place) {
        if (place == null || place.getPartnerBusinessId() == null) return Optional.empty();
        String provider = upper(place.getProvider());
        if (!PARTNER_ENRICHED_PROVIDERS.contains(provider)) return Optional.empty();
        return businessRepository.findById(place.getPartnerBusinessId())
                .filter(b -> b.getDeletedAt() == null)
                .filter(b -> APPROVED_STATUSES.contains(upper(b.getVerificationStatus())))
                .filter(b -> "PUBLISHED".equals(upper(b.getPublicationStatus())));
    }

    private PartnerPlaceProfileResponse toProfile(Long placeId, PartnerBusiness b) {
        List<HoursSlot> hours = hoursOf(b.getId());
        List<Note> notes = noteRepository.findByBusiness_IdOrderByCreatedAtDesc(b.getId()).stream()
                .filter(n -> Boolean.TRUE.equals(n.getActive()))
                .map(n -> new Note(n.getTitle(), n.getContent(), n.getNoteType()))
                .toList();
        List<Media> media = mediaRepository.findPublicImagesForBusinesses(List.of(b.getId())).stream()
                .map(m -> new Media(m.getMediaUrl(), m.getThumbnailUrl(), m.getCaption(),
                        Boolean.TRUE.equals(m.getCover())))
                .toList();
        return new PartnerPlaceProfileResponse(
                placeId, b.getId(), b.getName(), b.getBusinessType(),
                b.getShortDescription(), b.getDescription(),
                b.getAddress(), b.getWard(), b.getCity(), b.getProvince(),
                b.getLatitude(), b.getLongitude(),
                b.getPhoneNumber(), b.getEmail(), b.getWebsiteUrl(), b.getFacebookUrl(), b.getInstagramUrl(),
                b.getPriceLevel(), b.getOperatingStatus(),
                isOpenNow(hours, b.getOperatingStatus()),
                hours, notes, media);
    }

    private List<HoursSlot> hoursOf(Long businessId) {
        return hoursRepository.findByBusiness_IdOrderByDayOfWeekAsc(businessId).stream()
                .map(this::toSlot)
                .toList();
    }

    private HoursSlot toSlot(PartnerBusinessHours h) {
        return new HoursSlot(
                h.getDayOfWeek(),
                h.getOpenTime() == null ? null : h.getOpenTime().format(HH_MM),
                h.getCloseTime() == null ? null : h.getCloseTime().format(HH_MM),
                Boolean.TRUE.equals(h.getClosed()),
                Boolean.TRUE.equals(h.getClosesNextDay()));
    }

    /** null khi chưa có giờ mở cửa để không hiện sai "Đang đóng cửa". */
    private static Boolean isOpenNow(List<HoursSlot> hours, String operatingStatus) {
        if (!"OPEN".equals(upper(operatingStatus))) return false;
        if (hours.isEmpty()) return null;
        ZonedDateTime now = ZonedDateTime.now(VIETNAM);
        int today = now.getDayOfWeek().getValue();
        int yesterday = today == 1 ? 7 : today - 1;
        LocalTime time = now.toLocalTime();
        for (HoursSlot slot : hours) {
            if (slot.closed() || slot.openTime() == null || slot.closeTime() == null) continue;
            LocalTime open = LocalTime.parse(slot.openTime());
            LocalTime close = LocalTime.parse(slot.closeTime());
            if (slot.dayOfWeek() == today) {
                if (slot.closesNextDay() ? !time.isBefore(open) : (!time.isBefore(open) && time.isBefore(close))) {
                    return true;
                }
            } else if (slot.dayOfWeek() == yesterday && slot.closesNextDay() && time.isBefore(close)) {
                return true;
            }
        }
        return false;
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
