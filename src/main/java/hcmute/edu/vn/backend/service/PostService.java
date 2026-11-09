package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PostResponse;
import hcmute.edu.vn.backend.entity.Image;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.ImageRepository;
import hcmute.edu.vn.backend.repository.PostRepository;
import hcmute.edu.vn.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final CloudinaryService cloudinaryService;

    /**
     * Tạo bài đăng mới.
     *
     * Bài đăng sẽ ở trạng thái PENDING,
     * chờ Admin kiểm duyệt trước khi công khai.
     */
    @Transactional
    public Post createPost(
            String email,
            String caption,
            String locationName,
            Double latitude,
            Double longitude,
            Integer rating,
            List<MultipartFile> files
    ) throws Exception {

        User user = userRepository.findByEmail(email)
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

        validatePostData(
                latitude,
                longitude,
                rating
        );

        Post post = new Post();

        post.setUser(user);
        post.setCaption(caption);
        post.setLocationName(locationName);
        post.setLatitude(
                latitude != null ? BigDecimal.valueOf(latitude) : null
        );

        post.setLongitude(
                longitude != null ? BigDecimal.valueOf(longitude) : null
        );
        post.setRating(rating);

        // Chờ Admin duyệt.
        post.setModerationStatus("PENDING");

        Post savedPost = postRepository.save(post);

        // Upload ảnh/video nếu người dùng có đính kèm.
        if (files != null && !files.isEmpty()) {

            for (int i = 0; i < files.size(); i++) {

                MultipartFile file = files.get(i);

                if (file == null || file.isEmpty()) {
                    continue;
                }

                String contentType = file.getContentType();

                if (contentType == null
                        || (!contentType.startsWith("image/")
                        && !contentType.startsWith("video/"))) {

                    throw new IllegalArgumentException(
                            "Chỉ hỗ trợ upload ảnh và video."
                    );
                }

                // Upload lên Cloudinary.
                String secureUrl = cloudinaryService.uploadFile(
                        file,
                        "social_posts"
                );

                String mediaType = contentType.startsWith("video/")
                        ? "VIDEO"
                        : "IMAGE";

                Image image = new Image();

                image.setEntityType("POST");
                image.setEntityId(savedPost.getId());
                image.setImageUrl(secureUrl);
                image.setMediaType(mediaType);
                image.setIsCover(i == 0);

                imageRepository.save(image);
            }
        }

        return savedPost;
    }

    /**
     * Lấy danh sách bài đăng đã được Admin duyệt.
     *
     * Không trả bài PENDING, REJECTED hoặc HIDDEN.
     */
    @Transactional(readOnly = true)
    public List<PostResponse> getAllPosts() {

        List<Post> posts =
                postRepository
                        .findByModerationStatusOrderByCreatedAtDesc(
                                "APPROVED"
                        );

        return posts.stream()
                .map(this::toPostResponse)
                .toList();
    }

    /**
     * Chuyển Post Entity thành DTO.
     */
    private PostResponse toPostResponse(Post post) {

        PostResponse dto = new PostResponse();

        dto.setId(post.getId());

        dto.setAuthorName(
                post.getUser().getFullName()
        );

        dto.setAuthorAvatar(
                post.getUser().getAvatar()
        );

        dto.setCaption(post.getCaption());

        dto.setLocationName(post.getLocationName());

        dto.setLatitude(
                post.getLatitude() != null
                        ? post.getLatitude().doubleValue()
                        : null
        );

        dto.setLongitude(
                post.getLongitude() != null
                        ? post.getLongitude().doubleValue()
                        : null
        );

        dto.setRating(post.getRating());

        dto.setCreatedAt(post.getCreatedAt());

        List<Image> mediaFiles =
                imageRepository.findByEntityTypeAndEntityId(
                        "POST",
                        post.getId()
                );

        List<String> images = mediaFiles.stream()
                .filter(image ->
                        "IMAGE".equalsIgnoreCase(
                                image.getMediaType()
                        )
                )
                .map(Image::getImageUrl)
                .toList();

        List<String> videos = mediaFiles.stream()
                .filter(image ->
                        "VIDEO".equalsIgnoreCase(
                                image.getMediaType()
                        )
                )
                .map(Image::getImageUrl)
                .toList();

        dto.setImageUrls(images);
        dto.setVideoUrls(videos);

        return dto;
    }

    /**
     * Kiểm tra dữ liệu bài đăng.
     */
    private void validatePostData(
            Double latitude,
            Double longitude,
            Integer rating
    ) {

        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException(
                    "Latitude và longitude phải được nhập cùng nhau."
            );
        }

        if (latitude != null
                && (latitude < -90 || latitude > 90)) {

            throw new IllegalArgumentException(
                    "Latitude không hợp lệ."
            );
        }

        if (longitude != null
                && (longitude < -180 || longitude > 180)) {

            throw new IllegalArgumentException(
                    "Longitude không hợp lệ."
            );
        }

        if (rating != null
                && (rating < 1 || rating > 5)) {

            throw new IllegalArgumentException(
                    "Rating phải nằm trong khoảng từ 1 đến 5."
            );
        }
    }
}