package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.BookingRequest;
import hcmute.edu.vn.backend.dto.HotelRequest;
import hcmute.edu.vn.backend.dto.RoomRequest;
import hcmute.edu.vn.backend.service.HotelBookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/hotels")
public class HotelBookingController {

    @Autowired
    private HotelBookingService hotelBookingService;

    @PostMapping
    public ResponseEntity<?> addHotel(@RequestBody HotelRequest request, Principal principal) {
        try {
            return ResponseEntity.ok(hotelBookingService.addHotel(request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    @PostMapping("/{hotelId}/rooms")
    public ResponseEntity<?> addRoom(
            @PathVariable Long hotelId,
            @RequestBody RoomRequest request,
            Principal principal) {
        try {
            return ResponseEntity.ok(hotelBookingService.addRoom(hotelId, request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    @PostMapping("/bookings")
    public ResponseEntity<?> createBooking(@RequestBody BookingRequest request, Principal principal) {
        try {
            return ResponseEntity.ok(hotelBookingService.createBooking(request, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }

    @GetMapping("/bookings/my-bookings")
    public ResponseEntity<?> getMyBookings(Principal principal) {
        try {
            return ResponseEntity.ok(hotelBookingService.getMyBookings(principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Lỗi: " + e.getMessage());
        }
    }
}