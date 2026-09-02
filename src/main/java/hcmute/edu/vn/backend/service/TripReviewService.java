package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.ReviewRequest;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripReview;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.TripRepository;
import hcmute.edu.vn.backend.repository.TripReviewRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TripReviewService {

    @Autowired
    private TripReviewRepository tripReviewRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private UserRepository userRepository;

    // Logic gửi đánh giá
    public TripReview addReview(Long tripId, ReviewRequest request, String userEmail) throws Exception {
        if (request.getRating() < 1 || request.getRating() > 5) {
            throw new Exception("Số sao đánh giá phải từ 1 đến 5!");
        }

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        // Chỉ cho phép đánh giá các chuyến đi đã được public
        if (!trip.getIsPublic() || !"APPROVED".equals(trip.getPublicStatus())) {
            throw new Exception("Chỉ có thể đánh giá các lịch trình đã được công khai!");
        }

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng!"));

        // Kiểm tra xem đã đánh giá chưa. Nếu rồi thì cập nhật, chưa thì tạo mới.
        Optional<TripReview> existingReview = tripReviewRepository.findByTripIdAndUserId(tripId, user.getId());

        TripReview review;
        if (existingReview.isPresent()) {
            review = existingReview.get();
            review.setRating(request.getRating());
            review.setComment(request.getComment());
        } else {
            review = new TripReview();
            review.setTrip(trip);
            review.setUser(user);
            review.setRating(request.getRating());
            review.setComment(request.getComment());
        }

        return tripReviewRepository.save(review);
    }

    // Logic lấy danh sách đánh giá (Hiển thị ra ngoài giao diện cho mọi người xem)
    public List<TripReview> getReviewsByTrip(Long tripId) {
        return tripReviewRepository.findByTripId(tripId);
    }
}