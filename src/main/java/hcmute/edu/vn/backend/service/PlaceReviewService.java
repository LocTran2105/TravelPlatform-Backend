package hcmute.edu.vn.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import hcmute.edu.vn.backend.dto.PlaceReviewResponse;
import hcmute.edu.vn.backend.dto.ReviewMediaDto;
import hcmute.edu.vn.backend.entity.PlaceReview;
import hcmute.edu.vn.backend.entity.PlaceReviewMedia;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.entity.UserLike;
import hcmute.edu.vn.backend.repository.PlaceReviewMediaRepository;
import hcmute.edu.vn.backend.repository.PlaceReviewRepository;
import hcmute.edu.vn.backend.repository.UserLikeRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PlaceReviewService {

    // Giới hạn đính kèm cho mỗi đánh giá (đổi ở đây nếu cần; frontend cũng có hằng số tương ứng)
    private static final int MAX_FILES = 5;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024; // 10MB
    private static final long MAX_VIDEO_BYTES = 50L * 1024 * 1024; // 50MB

    @Autowired private PlaceReviewRepository placeReviewRepository;
    @Autowired private PlaceReviewMediaRepository placeReviewMediaRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserLikeRepository likeRepository;
    @Autowired private Cloudinary cloudinary;

    @Transactional(rollbackFor = Exception.class)
    public PlaceReview addOrUpdateReview(String locationName, Integer rating, String content, String email,
                                         List<MultipartFile> files) throws Exception {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng!"));

        // 1. Kiểm tra + upload file TRƯỚC khi ghi DB: nếu lỗi thì không có gì bị lưu dở
        List<MultipartFile> validFiles = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        validateFiles(validFiles);
        List<PlaceReviewMedia> uploaded = new ArrayList<>();
        for (MultipartFile file : validFiles) {
            uploaded.add(uploadToCloudinary(file));
        }

        // 2. Tìm xem user này đã đánh giá địa điểm này chưa
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

        PlaceReview saved = placeReviewRepository.save(review);

        // 3. Có file mới -> thay toàn bộ media cũ của đánh giá này; không có file -> giữ nguyên media cũ
        if (!uploaded.isEmpty()) {
            placeReviewMediaRepository.deleteByReviewId(saved.getId());
            for (PlaceReviewMedia media : uploaded) {
                media.setReview(saved);
            }
            placeReviewMediaRepository.saveAll(uploaded);
        }

        return saved;
    }

    public List<PlaceReviewResponse> getReviews(String locationName, String email) {
        User currentUser = email != null ? userRepository.findByEmail(email).orElse(null) : null;
        List<PlaceReview> reviews = placeReviewRepository.findByLocationNameOrderByCreatedAtDesc(locationName);

        // Gom media của tất cả đánh giá bằng 1 query
        Map<Long, List<ReviewMediaDto>> mediaByReview = new HashMap<>();
        if (!reviews.isEmpty()) {
            List<Long> ids = reviews.stream().map(PlaceReview::getId).toList();
            for (PlaceReviewMedia m : placeReviewMediaRepository.findByReviewIdInOrderByIdAsc(ids)) {
                mediaByReview
                        .computeIfAbsent(m.getReview().getId(), k -> new ArrayList<>())
                        .add(new ReviewMediaDto(m.getUrl(), m.getType()));
            }
        }

        return reviews.stream().map(r -> {
            PlaceReviewResponse dto = new PlaceReviewResponse();
            dto.setId(r.getId());
            dto.setAuthorName(r.getUser().getFullName());
            dto.setAuthorAvatar(r.getUser().getAvatar());
            dto.setContent(r.getContent());
            dto.setRating(r.getRating());
            dto.setCreatedAt(r.getCreatedAt());
            dto.setMedia(mediaByReview.getOrDefault(r.getId(), List.of()));

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

    // ================= HỖ TRỢ ẢNH / VIDEO =================

    private void validateFiles(List<MultipartFile> files) throws Exception {
        if (files.size() > MAX_FILES) {
            throw new Exception("Mỗi đánh giá chỉ được đính kèm tối đa " + MAX_FILES + " tệp!");
        }
        for (MultipartFile f : files) {
            String type = f.getContentType() == null ? "" : f.getContentType();
            boolean isImage = type.startsWith("image/");
            boolean isVideo = type.startsWith("video/");
            if (!isImage && !isVideo) {
                throw new Exception("\"" + f.getOriginalFilename() + "\" không phải ảnh hoặc video!");
            }
            long limit = isVideo ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES;
            if (f.getSize() > limit) {
                throw new Exception("\"" + f.getOriginalFilename() + "\" vượt quá " + (limit / 1024 / 1024) + "MB!");
            }
        }
    }

    /** Upload 1 file lên Cloudinary (tự nhận ảnh/video), trả về entity chưa gắn review. */
    private PlaceReviewMedia uploadToCloudinary(MultipartFile file) throws Exception {
        Map<?, ?> result = cloudinary.uploader().upload(
                file.getBytes(),
                ObjectUtils.asMap("resource_type", "auto", "folder", "place-reviews")
        );
        PlaceReviewMedia media = new PlaceReviewMedia();
        media.setUrl((String) result.get("secure_url"));
        media.setType("video".equals(result.get("resource_type")) ? "VIDEO" : "IMAGE");
        return media;
    }
}