package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.BookingRequest;
import hcmute.edu.vn.backend.dto.BookingStatusRequest;
import hcmute.edu.vn.backend.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /**
     * Khách hàng tạo booking.
     */
    @PostMapping("/bookings")
    public ResponseEntity<?> createBooking(
            @Valid @RequestBody BookingRequest request,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    bookingService.createBooking(
                            request,
                            principal.getName()
                    )
            );
        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Khách hàng xem booking của mình.
     */
    @GetMapping("/bookings/my-bookings")
    public ResponseEntity<?> getMyBookings(
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    bookingService.getMyBookings(
                            principal.getName()
                    )
            );
        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Khách hàng hủy booking.
     */
    @PutMapping("/bookings/{bookingId}/cancel")
    public ResponseEntity<?> cancelMyBooking(
            @PathVariable Long bookingId,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    bookingService.cancelMyBooking(
                            bookingId,
                            principal.getName()
                    )
            );
        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Partner xem booking của cơ sở kinh doanh.
     */
    @GetMapping("/partner/businesses/{businessId}/bookings")
    public ResponseEntity<?> getBusinessBookings(
            @PathVariable Long businessId,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    bookingService.getBusinessBookings(
                            businessId,
                            principal.getName()
                    )
            );
        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Partner xác nhận hoặc từ chối booking.
     */
    @PatchMapping(
            "/partner/businesses/{businessId}/bookings/{bookingId}/status"
    )
    public ResponseEntity<?> updateBookingStatus(
            @PathVariable Long businessId,
            @PathVariable Long bookingId,
            @Valid @RequestBody BookingStatusRequest request,
            Principal principal
    ) {
        try {
            if (request == null
                    || request.getStatus() == null
                    || request.getStatus().isBlank()) {

                return ResponseEntity.badRequest()
                        .body("Thiếu trạng thái booking.");
            }

            return ResponseEntity.ok(
                    bookingService.updateBookingStatusByPartner(
                            businessId,
                            bookingId,
                            request.getStatus(),
                            principal.getName()
                    )
            );

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }
}