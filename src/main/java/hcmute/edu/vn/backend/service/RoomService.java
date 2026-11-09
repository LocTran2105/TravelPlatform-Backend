package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.RoomRequest;
import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.Room;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.RoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RoomService {

    private final PartnerBusinessRepository businessRepository;
    private final RoomRepository roomRepository;
    private final PartnerAccessService partnerAccessService;

    /**
     * Thêm phòng mới.
     */
    @Transactional
    public Room addRoom(
            Long businessId,
            RoomRequest request,
            String userEmail
    ) {
        validateRequest(request);

        PartnerBusiness business =
                getOwnedBusiness(businessId, userEmail);

        if (!isAccommodationBusiness(business.getBusinessType())) {
            throw new IllegalStateException(
                    "Chỉ khách sạn, homestay, resort, hostel "
                            + "hoặc căn hộ mới được quản lý phòng."
            );
        }

        Room room = new Room();

        room.setBusiness(business);

        applyRequest(room, request);

        room.setStatus("ACTIVE");

        return roomRepository.save(room);
    }

    /**
     * Lấy danh sách phòng của Business.
     */
    @Transactional(readOnly = true)
    public List<Room> getBusinessRooms(
            Long businessId,
            String userEmail
    ) {
        getOwnedBusiness(businessId, userEmail);

        return roomRepository
                .findByBusiness_IdOrderByIdAsc(businessId);
    }

    /**
     * Cập nhật phòng.
     */
    @Transactional
    public Room updateRoom(
            Long businessId,
            Long roomId,
            RoomRequest request,
            String userEmail
    ) {
        validateRequest(request);

        getOwnedBusiness(businessId, userEmail);

        Room room = roomRepository
                .findByIdAndBusiness_Id(roomId, businessId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy phòng thuộc cơ sở kinh doanh này."
                        )
                );

        applyRequest(room, request);

        return roomRepository.save(room);
    }

    /**
     * Ngừng kinh doanh phòng.
     *
     * Không xóa vật lý để giữ lịch sử Booking.
     */
    @Transactional
    public void deactivateRoom(
            Long businessId,
            Long roomId,
            String userEmail
    ) {
        getOwnedBusiness(businessId, userEmail);

        Room room = roomRepository
                .findByIdAndBusiness_Id(roomId, businessId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy phòng thuộc cơ sở kinh doanh này."
                        )
                );

        room.setStatus("INACTIVE");

        roomRepository.save(room);
    }

    /**
     * Kiểm tra Business có thuộc tài khoản Partner không.
     */
    private PartnerBusiness getOwnedBusiness(
            Long businessId,
            String email
    ) {
        partnerAccessService.requireApprovedPartner(email);

        return businessRepository
                .findByIdAndPartner_User_EmailAndDeletedAtIsNull(
                        businessId,
                        email
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy cơ sở kinh doanh "
                                        + "hoặc bạn không có quyền quản lý."
                        )
                );
    }

    /**
     * Chỉ loại hình lưu trú mới được quản lý phòng.
     */
    private boolean isAccommodationBusiness(
            String businessType
    ) {
        if (businessType == null) {
            return false;
        }

        return switch (businessType.toUpperCase(Locale.ROOT)) {
            case "HOTEL",
                 "HOMESTAY",
                 "RESORT",
                 "HOSTEL",
                 "APARTMENT" -> true;

            default -> false;
        };
    }

    /**
     * Kiểm tra dữ liệu phòng.
     */
    private void validateRequest(RoomRequest request) {

        if (request == null
                || request.getRoomName() == null
                || request.getRoomName().isBlank()) {

            throw new IllegalArgumentException(
                    "Tên phòng không được để trống."
            );
        }

        BigDecimal price = request.getPricePerNight();

        if (price == null
                || price.compareTo(BigDecimal.ZERO) <= 0) {

            throw new IllegalArgumentException(
                    "Giá phòng mỗi đêm phải lớn hơn 0."
            );
        }

        if (request.getCapacity() != null
                && request.getCapacity() < 1) {

            throw new IllegalArgumentException(
                    "Sức chứa phải tối thiểu 1 người."
            );
        }

        if (request.getTotalUnits() != null
                && request.getTotalUnits() < 1) {

            throw new IllegalArgumentException(
                    "Số lượng phòng phải tối thiểu là 1."
            );
        }
    }

    /**
     * Gán dữ liệu từ Request vào Room.
     */
    private void applyRequest(
            Room room,
            RoomRequest request
    ) {
        room.setRoomName(request.getRoomName().trim());

        room.setDescription(
                trimToNull(request.getDescription())
        );

        room.setPricePerNight(request.getPricePerNight());

        room.setCurrency(
                request.getCurrency() == null
                        || request.getCurrency().isBlank()
                        ? "VND"
                        : request.getCurrency()
                        .trim()
                        .toUpperCase(Locale.ROOT)
        );

        room.setCapacity(
                request.getCapacity() == null
                        ? 2
                        : request.getCapacity()
        );

        room.setTotalUnits(
                request.getTotalUnits() == null
                        ? 1
                        : request.getTotalUnits()
        );
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}