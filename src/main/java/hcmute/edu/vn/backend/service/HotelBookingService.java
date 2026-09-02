package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.BookingRequest;
import hcmute.edu.vn.backend.dto.HotelRequest;
import hcmute.edu.vn.backend.dto.RoomRequest;
import hcmute.edu.vn.backend.entity.Booking;
import hcmute.edu.vn.backend.entity.Hotel;
import hcmute.edu.vn.backend.entity.Room;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.BookingRepository;
import hcmute.edu.vn.backend.repository.HotelRepository;
import hcmute.edu.vn.backend.repository.RoomRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class HotelBookingService {

    @Autowired private HotelRepository hotelRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private UserRepository userRepository;

    // 1. Thêm Khách sạn mới (Chỉ dành cho Đối tác - ROLE_PARTNER)
    public Hotel addHotel(HotelRequest request, String userEmail) throws Exception {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Người dùng không tồn tại!"));

        // Giả sử logic nghiệp vụ yêu cầu kiểm tra Role
        // if (!"ROLE_PARTNER".equals(user.getRole())) {
        //     throw new Exception("Chỉ tài khoản đối tác (Partner) mới được tạo khách sạn!");
        // }

        Hotel hotel = new Hotel();
        hotel.setPartner(user);
        hotel.setName(request.getName());
        hotel.setAddress(request.getAddress());
        hotel.setDescription(request.getDescription());
        return hotelRepository.save(hotel);
    }

    // 2. Thêm phòng vào Khách sạn
    public Room addRoom(Long hotelId, RoomRequest request, String userEmail) throws Exception {
        Hotel hotel = hotelRepository.findById(hotelId)
                .orElseThrow(() -> new Exception("Không tìm thấy khách sạn!"));

        // Kiểm tra quyền: Chỉ chủ khách sạn mới được thêm phòng
        if (!hotel.getPartner().getEmail().equals(userEmail)) {
            throw new Exception("Bạn không có quyền quản lý khách sạn này!");
        }

        Room room = new Room();
        room.setHotel(hotel);
        room.setRoomName(request.getRoomName());
        room.setPricePerNight(request.getPricePerNight());
        room.setCapacity(request.getCapacity());
        return roomRepository.save(room);
    }

    // 3. Đặt phòng (Booking)
    public Booking createBooking(BookingRequest request, String userEmail) throws Exception {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Người dùng không tồn tại!"));

        Room room = roomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new Exception("Không tìm thấy phòng!"));

        if (request.getCheckIn().isAfter(request.getCheckOut()) || request.getCheckIn().isEqual(request.getCheckOut())) {
            throw new Exception("Ngày Check-out phải sau ngày Check-in!");
        }

        // Tự động tính tổng tiền: (Số đêm) * (Giá 1 đêm)
        long numberOfNights = ChronoUnit.DAYS.between(request.getCheckIn(), request.getCheckOut());
        BigDecimal totalPrice = room.getPricePerNight().multiply(new BigDecimal(numberOfNights));

        Booking booking = new Booking();
        booking.setUser(user);
        booking.setRoom(room);
        booking.setCheckIn(request.getCheckIn());
        booking.setCheckOut(request.getCheckOut());
        booking.setTotalPrice(totalPrice);
        booking.setStatus("PENDING");

        return bookingRepository.save(booking);
    }

    // Lấy danh sách Booking của người dùng
    public List<Booking> getMyBookings(String userEmail) throws Exception {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Người dùng không tồn tại!"));
        return bookingRepository.findByUserId(user.getId());
    }
}