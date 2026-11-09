package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.BookingRequest;
import hcmute.edu.vn.backend.entity.Booking;
import hcmute.edu.vn.backend.entity.PartnerBusiness;
import hcmute.edu.vn.backend.entity.Room;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.BookingRepository;
import hcmute.edu.vn.backend.repository.PartnerBusinessRepository;
import hcmute.edu.vn.backend.repository.RoomRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BookingService {

    private static final List<String> RESERVING_STATUSES = List.of(
            "PENDING",
            "CONFIRMED"
    );

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final PartnerBusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final PartnerAccessService partnerAccessService;

    /**
     * Khách hàng tạo booking.
     */
    @Transactional
    public Booking createBooking(
            BookingRequest request,
            String userEmail
    ) {
        validateRequest(request);

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy người dùng."
                        )
                );

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalStateException(
                    "Tài khoản hiện không hoạt động."
            );
        }

        // Khóa phòng trong transaction để hạn chế đặt trùng.
        Room room = roomRepository
                .findByIdForUpdate(request.getRoomId())
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy phòng."
                        )
                );

        PartnerBusiness business = room.getBusiness();

        validateBusinessCanReceiveBooking(business);

        if (!"ACTIVE".equalsIgnoreCase(room.getStatus())) {
            throw new IllegalStateException(
                    "Phòng hiện không nhận đặt chỗ."
            );
        }

        int guestCount = request.getGuestCount() == null
                ? 1
                : request.getGuestCount();

        if (guestCount > room.getCapacity()) {
            throw new IllegalArgumentException(
                    "Số khách vượt quá sức chứa của phòng."
            );
        }

        long bookedUnits = bookingRepository.countConflictingBookings(
                room.getId(),
                RESERVING_STATUSES,
                request.getCheckOut(),
                request.getCheckIn()
        );

        int totalUnits = room.getTotalUnits() == null
                ? 1
                : room.getTotalUnits();

        if (bookedUnits >= totalUnits) {
            throw new IllegalStateException(
                    "Phòng đã hết chỗ trong khoảng thời gian bạn chọn."
            );
        }

        // Tính số đêm.
        long nights = ChronoUnit.DAYS.between(
                request.getCheckIn(),
                request.getCheckOut()
        );

        // Tính tổng tiền.
        BigDecimal totalPrice = room.getPricePerNight()
                .multiply(BigDecimal.valueOf(nights));

        Booking booking = new Booking();

        booking.setUser(user);
        booking.setRoom(room);
        booking.setCheckIn(request.getCheckIn());
        booking.setCheckOut(request.getCheckOut());
        booking.setGuestCount(guestCount);
        booking.setTotalPrice(totalPrice);

        booking.setCurrency(
                room.getCurrency() == null
                        ? "VND"
                        : room.getCurrency()
        );

        booking.setCustomerNote(
                trimToNull(request.getCustomerNote())
        );

        booking.setStatus("PENDING");

        return bookingRepository.save(booking);
    }

    /**
     * Khách hàng xem booking của mình.
     */
    @Transactional(readOnly = true)
    public List<Booking> getMyBookings(String userEmail) {

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy người dùng."
                        )
                );

        return bookingRepository
                .findByUser_IdOrderByCreatedAtDesc(user.getId());
    }

    /**
     * Partner xem booking thuộc cơ sở kinh doanh của mình.
     */
    @Transactional(readOnly = true)
    public List<Booking> getBusinessBookings(
            Long businessId,
            String partnerEmail
    ) {
        partnerAccessService.requireApprovedPartner(partnerEmail);

        requireOwnedBusiness(businessId, partnerEmail);

        return bookingRepository
                .findByRoom_Business_IdOrderByCreatedAtDesc(
                        businessId
                );
    }

    /**
     * Khách hàng hủy booking đang chờ xác nhận.
     */
    @Transactional
    public Booking cancelMyBooking(
            Long bookingId,
            String userEmail
    ) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy người dùng."
                        )
                );

        Booking booking = bookingRepository
                .findByIdAndUser_Id(
                        bookingId,
                        user.getId()
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy đơn đặt phòng của bạn."
                        )
                );

        if (!"PENDING".equalsIgnoreCase(booking.getStatus())) {
            throw new IllegalStateException(
                    "Chỉ có thể tự hủy đơn đang ở trạng thái PENDING. "
                            + "Vui lòng liên hệ cơ sở lưu trú "
                            + "nếu đơn đã xác nhận."
            );
        }

        booking.setStatus("CANCELLED");

        return bookingRepository.save(booking);
    }

    /**
     * Partner xác nhận hoặc từ chối booking.
     */
    @Transactional
    public Booking updateBookingStatusByPartner(
            Long businessId,
            Long bookingId,
            String newStatus,
            String partnerEmail
    ) {
        partnerAccessService.requireApprovedPartner(partnerEmail);

        requireOwnedBusiness(businessId, partnerEmail);

        Booking booking = bookingRepository
                .findByIdAndRoom_Business_Id(
                        bookingId,
                        businessId
                )
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Không tìm thấy booking thuộc cơ sở kinh doanh này."
                        )
                );

        String normalizedStatus = newStatus == null
                ? ""
                : newStatus.trim().toUpperCase(Locale.ROOT);

        if (!List.of(
                "CONFIRMED",
                "REJECTED",
                "CANCELLED"
        ).contains(normalizedStatus)) {

            throw new IllegalArgumentException(
                    "Trạng thái mới chỉ được là "
                            + "CONFIRMED, REJECTED hoặc CANCELLED."
            );
        }

        if (!"PENDING".equalsIgnoreCase(booking.getStatus())) {
            throw new IllegalStateException(
                    "Chỉ có thể xử lý booking đang ở trạng thái PENDING."
            );
        }

        booking.setStatus(normalizedStatus);

        return bookingRepository.save(booking);
    }

    /**
     * Kiểm tra Business có thuộc Partner đang đăng nhập.
     */
    private PartnerBusiness requireOwnedBusiness(
            Long businessId,
            String email
    ) {
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
     * Chỉ Business đã duyệt, công khai và đang mở cửa
     * mới được nhận booking.
     */
    private void validateBusinessCanReceiveBooking(
            PartnerBusiness business
    ) {
        if (business == null || business.getDeletedAt() != null) {
            throw new IllegalStateException(
                    "Cơ sở kinh doanh không còn hoạt động."
            );
        }

        if (!"APPROVED".equalsIgnoreCase(
                business.getVerificationStatus()
        )
                || !"PUBLISHED".equalsIgnoreCase(
                business.getPublicationStatus()
        )
                || !"OPEN".equalsIgnoreCase(
                business.getOperatingStatus()
        )) {

            throw new IllegalStateException(
                    "Cơ sở kinh doanh chưa được duyệt, "
                            + "chưa công khai hoặc đang đóng cửa."
            );
        }
    }

    /**
     * Kiểm tra dữ liệu booking.
     */
    private void validateRequest(BookingRequest request) {

        if (request == null || request.getRoomId() == null) {
            throw new IllegalArgumentException(
                    "Bạn chưa chọn phòng."
            );
        }

        if (request.getCheckIn() == null
                || request.getCheckOut() == null) {

            throw new IllegalArgumentException(
                    "Vui lòng cung cấp ngày nhận phòng "
                            + "và ngày trả phòng."
            );
        }

        if (!request.getCheckOut().isAfter(
                request.getCheckIn()
        )) {
            throw new IllegalArgumentException(
                    "Ngày trả phòng phải sau ngày nhận phòng."
            );
        }

        if (request.getCheckIn().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException(
                    "Ngày nhận phòng không được ở trong quá khứ."
            );
        }

        if (request.getGuestCount() != null
                && request.getGuestCount() < 1) {

            throw new IllegalArgumentException(
                    "Số khách phải tối thiểu là 1."
            );
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}