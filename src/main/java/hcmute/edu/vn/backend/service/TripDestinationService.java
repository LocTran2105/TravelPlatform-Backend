package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.DestinationRequest;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripDay;
import hcmute.edu.vn.backend.entity.TripDestination;
import hcmute.edu.vn.backend.repository.PlaceRepository; // Thêm repo này
import hcmute.edu.vn.backend.repository.TripDayRepository;
import hcmute.edu.vn.backend.repository.TripDestinationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;

@Service
public class TripDestinationService {
    @Autowired
    private TripDestinationRepository tripDestinationRepository;

    @Autowired
    private TripDayRepository tripDayRepository;

    @Autowired
    private TripAccessService accessService;

    @Autowired
    private TripActivityService activityService;

    @Autowired
    private PlaceRepository placeRepository; // Inject thêm PlaceRepository

    @Transactional(rollbackFor = Exception.class)
    public TripDestination addDestinationToDay(Long dayId, DestinationRequest request, String userEmail) throws Exception {
        if (request == null) {
            throw new Exception("Dữ liệu địa điểm không hợp lệ!");
        }

        TripDay tripDay = tripDayRepository.findById(dayId)
                .orElseThrow(() -> new Exception("Không tìm thấy ngày của chuyến đi!"));

        // Chủ chuyến đi hoặc tripmate được chỉnh sửa (người chỉ xem bị từ chối)
        accessService.requireEdit(tripDay.getTrip(), userEmail);

        Place place = null;

        if (request.getPlaceId() != null) {
            place = placeRepository.findById(request.getPlaceId())
                    .orElseThrow(() ->
                            new Exception("Không tìm thấy địa điểm đề xuất!")
                    );

            if (place.getName() == null || place.getName().isBlank()) {
                throw new Exception("Địa điểm không có tên hợp lệ!");
            }
        } else if (request.getPlaceName() == null
                || request.getPlaceName().isBlank()) {
            throw new Exception("Tên địa điểm không được để trống!");
        }

        if ((request.getLatitude() == null)
                != (request.getLongitude() == null)) {
            throw new Exception(
                    "Latitude và longitude phải được nhập cùng nhau!"
            );
        }

        if (request.getLatitude() != null) {
            double lat = request.getLatitude();
            double lon = request.getLongitude();

            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                throw new Exception("Tọa độ địa điểm không hợp lệ!");
            }
        }

        if (request.getOrderIndex() != null && request.getOrderIndex() < 0) {
            throw new Exception("Thứ tự địa điểm không hợp lệ!");
        }

        if (request.getStartTime() != null
                && request.getEndTime() != null
                && !request.getEndTime().isAfter(request.getStartTime())) {
            throw new Exception("Giờ kết thúc phải sau giờ bắt đầu!");
        }

        TripDestination dest = new TripDestination();
        dest.setTripDay(tripDay);
        dest.setPlace(place);

        if (place != null) {
            // Place là nguồn dữ liệu chính thức khi thêm từ Recommendation.
            dest.setPlaceName(place.getName());
            dest.setLatitude(place.getLatitude());
            dest.setLongitude(place.getLongitude());

            String placeType = place.getPlaceType();

            dest.setItemType(
                    placeType == null || placeType.isBlank()
                            ? "ATTRACTION"
                            : placeType.trim().toUpperCase(Locale.ROOT)
            );

            dest.setNote(
                    request.getNote() != null && !request.getNote().isBlank()
                            ? request.getNote().trim()
                            : place.getAddress()
            );

            // Ưu tiên chi phí tham khảo đã lưu trong Place.
            dest.setEstimatedCost(
                    place.getEstimatedCostMin() != null
                            ? place.getEstimatedCostMin()
                            : request.getEstimatedCost()
            );

        } else {
            // Vẫn hỗ trợ địa điểm tự nhập không thuộc bảng places.
            dest.setPlaceName(request.getPlaceName().trim());

            dest.setLatitude(
                    request.getLatitude() == null
                            ? null
                            : BigDecimal.valueOf(request.getLatitude())
            );

            dest.setLongitude(
                    request.getLongitude() == null
                            ? null
                            : BigDecimal.valueOf(request.getLongitude())
            );

            String itemType = request.getItemType();

            dest.setItemType(
                    itemType == null || itemType.isBlank()
                            ? "ATTRACTION"
                            : itemType.trim().toUpperCase(Locale.ROOT)
            );

            dest.setNote(request.getNote());
            dest.setEstimatedCost(request.getEstimatedCost());
        }

        BigDecimal cost = dest.getEstimatedCost();

        if (cost != null
                && (cost.signum() < 0 || cost.compareTo(MAX_MONEY) > 0)) {
            throw new Exception("Chi phí ước tính không hợp lệ!");
        }

        int nextOrder = tripDestinationRepository
                .findByTripDay_IdOrderByOrderIndexAsc(dayId)
                .size();

        dest.setOrderIndex(
                request.getOrderIndex() != null
                        ? request.getOrderIndex()
                        : nextOrder
        );

        dest.setStartTime(request.getStartTime());
        dest.setEndTime(request.getEndTime());
        dest.setIsVisited(Boolean.TRUE.equals(request.getIsVisited()));

        dest.setTimeRange(request.getTimeRange());
        dest.setCost(request.getCost());

        TripDestination saved = tripDestinationRepository.save(dest);
        activityService.log(tripDay.getTrip(), userEmail, TripActivityService.DESTINATION_ADDED,
                "DESTINATION", saved.getId(), saved.getPlaceName(), "Day " + tripDay.getDayNumber());
        return saved;
    }


    private static final BigDecimal MAX_MONEY = new BigDecimal("1000000000000");

    @Transactional(rollbackFor = Exception.class)
    public void updateDestinationProperty(Long destinationId, String field, Object value, String userEmail) throws Exception {
        TripDestination dest = tripDestinationRepository.findById(destinationId)
                .orElseThrow(() -> new Exception("Không tìm thấy địa điểm trong lịch trình!"));

        Trip trip = dest.getTripDay().getTrip();
        // Chủ chuyến đi hoặc tripmate được chỉnh sửa (người chỉ xem bị từ chối)
        accessService.requireEdit(trip, userEmail);

        // Ghi lại giá trị cũ để chỉ ghi lịch sử khi có thay đổi thật
        // (ô ghi chú gửi lên mỗi lần rời ô nhập, kể cả khi chưa sửa gì)
        String action = null;
        String detail = null;
        boolean changed = false;
        boolean coalesce = true;

        switch (field) {
            case "note": {
                String newNote = value != null ? value.toString() : null;
                changed = !Objects.equals(blankToNull(dest.getNote()), blankToNull(newNote));
                dest.setNote(newNote);
                action = TripActivityService.DESTINATION_NOTE_CHANGED;
                break;
            }
            case "isVisited": {
                boolean newVisited = Boolean.parseBoolean(String.valueOf(value));
                changed = !Objects.equals(dest.getIsVisited(), newVisited);
                dest.setIsVisited(newVisited);
                action = newVisited ? TripActivityService.DESTINATION_VISITED : TripActivityService.DESTINATION_UNVISITED;
                coalesce = false;
                break;
            }
            case "time": { // Giờ ghé thăm dạng "08:00 - 10:00" (ô giờ trong TripDetail)
                String newTime = value != null ? value.toString() : null;
                changed = !Objects.equals(blankToNull(dest.getTimeRange()), blankToNull(newTime));
                dest.setTimeRange(newTime);
                action = TripActivityService.DESTINATION_TIME_CHANGED;
                detail = blankToNull(newTime);
                break;
            }
            case "cost": { // Cũ
                String newCost = value != null ? value.toString() : null;
                changed = !Objects.equals(blankToNull(dest.getCost()), blankToNull(newCost));
                dest.setCost(newCost);
                action = TripActivityService.DESTINATION_COST_CHANGED;
                detail = blankToNull(newCost);
                break;
            }
            case "estimatedCost": {
                BigDecimal newCost = parseMoney(value);
                changed = dest.getEstimatedCost() == null || dest.getEstimatedCost().compareTo(newCost) != 0;
                dest.setEstimatedCost(newCost);
                action = TripActivityService.DESTINATION_COST_CHANGED;
                detail = newCost.stripTrailingZeros().toPlainString();
                break;
            }
            // --- LOGIC MỚI CHO CÁC TRƯỜNG THỜI GIAN/LOẠI ---
            case "startTime": {
                LocalTime newStart = parseTime(value);
                changed = !Objects.equals(dest.getStartTime(), newStart);
                dest.setStartTime(newStart);
                action = TripActivityService.DESTINATION_TIME_CHANGED;
                detail = formatTimeSlot(dest.getStartTime(), dest.getEndTime());
                break;
            }
            case "endTime": {
                LocalTime newEnd = parseTime(value);
                changed = !Objects.equals(dest.getEndTime(), newEnd);
                dest.setEndTime(newEnd);
                action = TripActivityService.DESTINATION_TIME_CHANGED;
                detail = formatTimeSlot(dest.getStartTime(), dest.getEndTime());
                break;
            }
            case "itemType":
                dest.setItemType(value != null ? value.toString() : "ATTRACTION");
                break;
            // ------------------------------------------------
            default:
                throw new Exception("Trường cần cập nhật không hợp lệ!");
        }

        tripDestinationRepository.save(dest);

        if (changed && action != null) {
            if (coalesce) {
                activityService.logCoalesced(trip, userEmail, action, "DESTINATION", dest.getId(), dest.getPlaceName(), detail);
            } else {
                activityService.log(trip, userEmail, action, "DESTINATION", dest.getId(), dest.getPlaceName(), detail);
            }
        }
    }

    /**
     * Xóa một địa điểm khỏi lịch trình (chủ chuyến đi hoặc tripmate; người chỉ xem bị từ chối).
     * Chỉ xóa dòng trip_destinations: Place gốc trong bảng places được giữ nguyên, khoản chi đã gắn
     * vẫn còn vì FK expenses.trip_destination_id là ON DELETE SET NULL.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDestination(Long destinationId, String userEmail) throws Exception {
        TripDestination dest = tripDestinationRepository.findById(destinationId)
                .orElseThrow(() -> new Exception("Không tìm thấy địa điểm trong lịch trình!"));

        TripDay tripDay = dest.getTripDay();
        Trip trip = tripDay.getTrip();
        accessService.requireEdit(trip, userEmail);

        // TripDay.destinations có cascade ALL: gỡ khỏi collection để Hibernate không lưu lại khi flush.
        if (tripDay.getDestinations() != null) {
            tripDay.getDestinations().remove(dest);
        }
        tripDestinationRepository.delete(dest);

        // Ghi lịch sử thao tác: bật dòng dưới nếu TripActivityService có hằng số cho thao tác xóa
        // activityService.log(trip, userEmail, TripActivityService.DESTINATION_REMOVED,
        //         "DESTINATION", destinationId, dest.getPlaceName(), "Day " + tripDay.getDayNumber());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** "08:00 - 10:00", "08:00" hoặc null nếu chưa có giờ nào. */
    private static String formatTimeSlot(LocalTime start, LocalTime end) {
        if (start == null && end == null) return null;
        if (start != null && end != null) return start + " - " + end;
        return String.valueOf(start != null ? start : end);
    }

    private BigDecimal parseMoney(Object value) throws Exception {
        if (value == null || value.toString().isBlank()) return BigDecimal.ZERO;
        BigDecimal amount;
        try {
            amount = new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new Exception("Số tiền không hợp lệ!");
        }
        if (amount.signum() < 0) throw new Exception("Số tiền không được âm!");
        if (amount.compareTo(MAX_MONEY) > 0) throw new Exception("Số tiền quá lớn!");
        return amount;
    }

    // Hàm phụ trợ parse chuỗi giờ (vd: "14:30") sang LocalTime
    private LocalTime parseTime(Object value) throws Exception {
        if (value == null || value.toString().isBlank()) return null;
        try {
            return LocalTime.parse(value.toString().trim());
        } catch (DateTimeParseException e) {
            throw new Exception("Định dạng giờ không hợp lệ (Cần HH:mm)!");
        }
    }
}