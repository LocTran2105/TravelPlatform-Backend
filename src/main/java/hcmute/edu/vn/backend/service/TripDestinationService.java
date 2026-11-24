package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.DestinationRequest;
import hcmute.edu.vn.backend.entity.Place;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripDay;
import hcmute.edu.vn.backend.entity.TripDestination;
import hcmute.edu.vn.backend.repository.PlaceRepository; // Thêm repo này
import hcmute.edu.vn.backend.repository.TripDayRepository;
import hcmute.edu.vn.backend.repository.TripDestinationRepository;
import hcmute.edu.vn.backend.repository.TripMemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;

@Service
public class TripDestinationService {
    @Autowired
    private TripDestinationRepository tripDestinationRepository;

    @Autowired
    private TripDayRepository tripDayRepository;

    @Autowired
    private TripMemberRepository tripMemberRepository;

    @Autowired
    private PlaceRepository placeRepository; // Inject thêm PlaceRepository


    @Transactional
    public TripDestination addDestinationToDay(
            Long dayId,
            DestinationRequest request,
            String userEmail
    ) throws Exception {

        if (request == null) {
            throw new Exception("Dữ liệu địa điểm không hợp lệ!");
        }

        TripDay tripDay = tripDayRepository.findById(dayId)
                .orElseThrow(() ->
                        new Exception("Không tìm thấy ngày của chuyến đi!")
                );

        Trip trip = tripDay.getTrip();
        Long tripId = trip.getId();

        boolean isOwner = trip.getUser() != null
                && trip.getUser().getEmail().equalsIgnoreCase(userEmail);

        boolean isCompanion =
                tripMemberRepository.existsByTripIdAndUserEmail(
                        tripId, userEmail
                );

        if (!isOwner && !isCompanion) {
            throw new Exception(
                    "Bạn không có quyền chỉnh sửa hành trình này!"
            );
        }

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

        return tripDestinationRepository.save(dest);
    }


    private static final BigDecimal MAX_MONEY = new BigDecimal("1000000000000");

    @Transactional
    public void updateDestinationProperty(Long destinationId, String field, Object value, String userEmail) throws Exception {
        TripDestination dest = tripDestinationRepository.findById(destinationId)
                .orElseThrow(() -> new Exception("Không tìm thấy địa điểm trong lịch trình!"));

        Trip trip = dest.getTripDay().getTrip();
        boolean isOwner = trip.getUser().getEmail().equalsIgnoreCase(userEmail);
        boolean isCompanion = tripMemberRepository.existsByTripIdAndUserEmail(trip.getId(), userEmail);
        if (!isOwner && !isCompanion) {
            throw new Exception("Bạn không có quyền chỉnh sửa hành trình này!");
        }

        switch (field) {
            case "note":
                dest.setNote(value != null ? value.toString() : null);
                break;
            case "isVisited":
                dest.setIsVisited(Boolean.valueOf(value.toString()));
                break;
            case "time": // Cũ
                dest.setTimeRange(value != null ? value.toString() : null);
                break;
            case "cost": // Cũ
                dest.setCost(value != null ? value.toString() : null);
                break;
            case "estimatedCost":
                dest.setEstimatedCost(parseMoney(value));
                break;
            // --- LOGIC MỚI CHO CÁC TRƯỜNG THỜI GIAN/LOẠI ---
            case "startTime":
                dest.setStartTime(parseTime(value));
                break;
            case "endTime":
                dest.setEndTime(parseTime(value));
                break;
            case "itemType":
                dest.setItemType(value != null ? value.toString() : "ATTRACTION");
                break;
            // ------------------------------------------------
            default:
                throw new Exception("Trường cần cập nhật không hợp lệ!");
        }

        tripDestinationRepository.save(dest);
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