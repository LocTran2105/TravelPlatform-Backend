package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PlaceReviewResponse;
import hcmute.edu.vn.backend.entity.PlaceReview;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.entity.UserLike;
import hcmute.edu.vn.backend.repository.PlaceReviewRepository;
import hcmute.edu.vn.backend.repository.UserLikeRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class PlaceReviewService {

    @Autowired private PlaceReviewRepository placeReviewRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserLikeRepository likeRepository;

    @Transactional
    public PlaceReview addOrUpdateReview(String locationName, Integer rating, String content, String email) throws Exception {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng!"));

        // Tìm xem user này đã đánh giá địa điểm này chưa
        Optional<PlaceReview> existingReview = placeReviewRepository.findByLocationNameAndUserId(locationName, user.getId());
        PlaceReview review;

        if (existingReview.isPresent()) {
            // Đã có -> Bỏ đánh giá cũ đi (Ghi đè nội dung & số sao mới)
            review = existingReview.get();
            review.setRating(rating);
            review.setContent(content);
        } else {
            // Chưa có -> Tạo mới hoàn toàn
            review = new PlaceReview();
            review.setUser(user);
            review.setLocationName(locationName);
            review.setRating(rating);
            review.setContent(content);
        }

        return placeReviewRepository.save(review);
    }

    public List<PlaceReviewResponse> getReviews(String locationName, String email) {
        User currentUser = email != null ? userRepository.findByEmail(email).orElse(null) : null;
        List<PlaceReview> reviews = placeReviewRepository.findByLocationNameOrderByCreatedAtDesc(locationName);

        return reviews.stream().map(r -> {
            PlaceReviewResponse dto = new PlaceReviewResponse();
            dto.setId(r.getId());
            dto.setAuthorName(r.getUser().getFullName());
            dto.setAuthorAvatar(r.getUser().getAvatar());
            dto.setContent(r.getContent());
            dto.setRating(r.getRating());
            dto.setCreatedAt(r.getCreatedAt());

            dto.setLikesCount(likeRepository.countByTargetTypeAndTargetId("REVIEW", r.getId()));

            if (currentUser != null) {
                dto.setLikedByMe(likeRepository.findByUserIdAndTargetTypeAndTargetId(currentUser.getId(), "REVIEW", r.getId()).isPresent());
            }
            return dto;
        }).toList();
    }

    @Transactional
    public String toggleLike(Long reviewId, String email) throws Exception {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng!"));

        Optional<UserLike> existingLike = likeRepository.findByUserIdAndTargetTypeAndTargetId(user.getId(), "REVIEW", reviewId);

        if (existingLike.isPresent()) {
            likeRepository.delete(existingLike.get());
            return "UNLIKED";
        } else {
            UserLike newLike = new UserLike();
            newLike.setUser(user);
            newLike.setTargetType("REVIEW");
            newLike.setTargetId(reviewId);
            likeRepository.save(newLike);
            return "LIKED";
        }
    }
}