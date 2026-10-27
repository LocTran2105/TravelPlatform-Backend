package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.ReviewRequest;
import hcmute.edu.vn.backend.dto.ReviewResponse;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.TripReview;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.TripRepository;
import hcmute.edu.vn.backend.repository.TripReviewRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class TripReviewService {

    @Autowired
    private TripReviewRepository tripReviewRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private UserRepository userRepository;

    // Logic gửi/cập nhật đánh giá
    @Transactional
    public ReviewResponse addOrUpdateReview(Long tripId, ReviewRequest request, String userEmail) throws Exception {
        if (request.getRating() < 1 || request.getRating() > 5) {
            throw new Exception("Số sao đánh giá phải từ 1 đến 5!");
        }

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new Exception("Không tìm thấy chuyến đi!"));

        if (!trip.getIsPublic() || !"APPROVED".equals(trip.getPublicStatus())) {
            throw new Exception("Chỉ có thể đánh giá các lịch trình đã được công khai!");
        }

        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng!"));

        Optional<TripReview> existingReview = tripReviewRepository.findByTripIdAndUserId(tripId, user.getId());
        TripReview review;

        if (existingReview.isPresent()) {
            // Sửa đánh giá cũ
            review = existingReview.get();
            int oldRating = review.getRating();

            review.setRating(request.getRating());
            review.setComment(request.getComment());

            // Tính lại trung bình khi đổi sao: (Tổng điểm cũ - sao cũ + sao mới) / Tổng số review
            if (oldRating != request.getRating()) {
                double totalPoints = (trip.getAverageRating() * trip.getTotalReviews()) - oldRating + request.getRating();
                trip.setAverageRating(Math.round((totalPoints / trip.getTotalReviews()) * 10.0) / 10.0);
            }
        } else {
            // Thêm đánh giá mới
            review = new TripReview();
            review.setTrip(trip);
            review.setUser(user);
            review.setRating(request.getRating());
            review.setComment(request.getComment());

            // Tính lại trung bình cho đánh giá mới
            int newTotal = trip.getTotalReviews() == null ? 0 : trip.getTotalReviews() + 1;
            double currentAverage = trip.getAverageRating() == null ? 0.0 : trip.getAverageRating();
            double newAverage = ((currentAverage * (newTotal - 1)) + request.getRating()) / newTotal;

            trip.setTotalReviews(newTotal);
            trip.setAverageRating(Math.round(newAverage * 10.0) / 10.0);
        }

        tripRepository.save(trip);
        TripReview savedReview = tripReviewRepository.save(review);

        return new ReviewResponse(
                savedReview.getId(),
                user.getId(),
                user.getFullName(),
                user.getAvatar(),
                savedReview.getRating(),
                savedReview.getComment(),
                savedReview.getCreatedAt() != null ? savedReview.getCreatedAt() : LocalDateTime.now()
        );
    }

    // Logic lấy danh sách có phân trang (Chống sập server khi dữ liệu lớn)
    public Page<ReviewResponse> getReviewsByTrip(Long tripId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<TripReview> reviewPage = tripReviewRepository.findByTripIdWithUser(tripId, pageable);

        return reviewPage.map(review -> new ReviewResponse(
                review.getId(),
                review.getUser().getId(),
                review.getUser().getFullName(),
                review.getUser().getAvatar(),
                review.getRating(),
                review.getComment(),
                review.getCreatedAt()
        ));
    }
}