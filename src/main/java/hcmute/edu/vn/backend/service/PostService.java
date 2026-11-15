package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PostResponse;
import hcmute.edu.vn.backend.entity.Image;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.FollowRepository;
import hcmute.edu.vn.backend.repository.ImageRepository;
import hcmute.edu.vn.backend.repository.PostRepository;
import hcmute.edu.vn.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PostService {

    private static final Set<String> VISIBILITIES =
            Set.of("PUBLIC", "FOLLOWERS", "FRIENDS", "PRIVATE");

    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_DELETED = "DELETED";

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final CloudinaryService cloudinaryService;
    private final FollowRepository followRepository;

    /** Quan hệ giữa người xem và tác giả, tính một lần cho mỗi tác giả. */
    private record Relation(boolean followedByMe, boolean followingMe, String label) {}

    // =====================================================================
    // TẠO BÀI
    // =====================================================================

    /**
     * Tạo bài đăng mới.
     * Quyền hiển thị được kiểm tra trước khi lưu: giá trị lạ trả về 400
     * thay vì để DB ném lỗi CHECK constraint (500).
     */
    @Transactional
    public Post createPost(
            String email,
            String caption,
            String locationName,
            Double latitude,
            Double longitude,
            Integer rating,
            String visibility,
            List<MultipartFile> files
    ) throws Exception {

        User user = requireActiveUser(email);

        validatePostData(latitude, longitude, rating);
        String normalizedVisibility = normalizeVisibility(visibility);

        boolean hasFile = files != null
                && files.stream().anyMatch(f -> f != null && !f.isEmpty());
        boolean hasCaption = caption != null && !caption.isBlank();
        if (!hasCaption && !hasFile) {
            throw new IllegalArgumentException("Bài viết cần có nội dung hoặc ảnh/video.");
        }

        // Kiểm tra loại file TRƯỚC khi lưu bài / upload để không bị upload dở dang.
        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;
                String contentType = file.getContentType();
                if (contentType == null
                        || (!contentType.startsWith("image/") && !contentType.startsWith("video/"))) {
                    throw new IllegalArgumentException("Chỉ hỗ trợ upload ảnh và video.");
                }
            }
        }

        Post post = new Post();
        post.setUser(user);
        post.setCaption(hasCaption ? caption.trim() : null);
        post.setLocationName(locationName);
        post.setLatitude(latitude != null ? BigDecimal.valueOf(latitude) : null);
        post.setLongitude(longitude != null ? BigDecimal.valueOf(longitude) : null);
        post.setRating(rating);
        post.setVisibility(normalizedVisibility);
        post.setStatus(STATUS_PUBLISHED);

        // Hiện tại Post.prePersist() tự chuyển PENDING -> APPROVED.
        // Khi làm kiểm duyệt: bỏ đoạn đó trong Post.java để PENDING có hiệu lực.
        post.setModerationStatus("PENDING");

        Post savedPost = postRepository.save(post);

        if (files != null) {
            boolean coverSet = false;
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;

                String contentType = file.getContentType();
                String secureUrl = cloudinaryService.uploadFile(file, "social_posts");

                Image image = new Image();
                image.setEntityType("POST");
                image.setEntityId(savedPost.getId());
                image.setImageUrl(secureUrl);
                image.setMediaType(contentType.startsWith("video/") ? "VIDEO" : "IMAGE");
                image.setIsCover(!coverSet);
                coverSet = true;

                imageRepository.save(image);
            }
        }

        return savedPost;
    }

    // =====================================================================
    // SỬA / XÓA MỀM
    // =====================================================================

    /**
     * Sửa bài: chỉ chủ bài, chỉ khi bài chưa bị xóa.
     * Hiện hỗ trợ sửa nội dung và quyền hiển thị.
     * (Khi làm kiểm duyệt: có thể đặt lại moderationStatus = PENDING ở đây.)
     */
    @Transactional
    public PostResponse updatePost(
            String email,
            Long postId,
            String caption,
            String visibility
    ) {
        User user = requireActiveUser(email);
        Post post = requireOwnedPublishedPost(user, postId, "chỉnh sửa");

        if (caption != null) {
            post.setCaption(caption.trim());
        }
        if (visibility != null && !visibility.isBlank()) {
            post.setVisibility(normalizeVisibility(visibility));
        }

        boolean hasCaption = post.getCaption() != null && !post.getCaption().isBlank();
        boolean hasMedia = !imageRepository
                .findByEntityTypeAndEntityId("POST", post.getId()).isEmpty();
        if (!hasCaption && !hasMedia) {
            throw new IllegalArgumentException("Bài viết cần có nội dung hoặc ảnh/video.");
        }

        Post saved = postRepository.save(post);
        return toPostResponse(saved, user, new HashMap<>());
    }

    /** Xóa mềm: chỉ đổi status = DELETED, dữ liệu vẫn nằm trong DB. */
    @Transactional
    public void deletePost(String email, Long postId) {
        User user = requireActiveUser(email);
        Post post = requireOwnedPublishedPost(user, postId, "xóa");
        post.setStatus(STATUS_DELETED);
        postRepository.save(post);
    }

    // =====================================================================
    // ĐỌC
    // =====================================================================

    /**
     * Bảng tin cộng đồng: lọc theo status, kiểm duyệt VÀ quyền hiển thị
     * so với người đang xem (currentUserEmail có thể null = khách).
     */
    @Transactional(readOnly = true)
    public List<PostResponse> getAllPosts(String currentUserEmail) {

        User currentUser = findUserOrNull(currentUserEmail);
        Long viewerId = currentUser != null ? currentUser.getId() : -1L;

        List<Post> posts = postRepository.findFeedForViewer(viewerId);

        Map<Long, Relation> cache = new HashMap<>();
        return posts.stream()
                .map(post -> toPostResponse(post, currentUser, cache))
                .toList();
    }

    /** Bài viết của một người, có áp dụng quyền hiển thị. */
    @Transactional(readOnly = true)
    public List<PostResponse> getUserPosts(String targetUsername, String currentUserEmail) {

        boolean isOwner = false;
        boolean isFollower = false;
        boolean isFriend = false; // Friend = follow chéo 2 chiều

        User targetUser = userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng."));

        User currentUser = findUserOrNull(currentUserEmail);
        if (currentUser != null) {
            if (currentUser.getId().equals(targetUser.getId())) {
                isOwner = true;
            } else {
                isFollower = followRepository.existsByFollowerAndFollowed(currentUser, targetUser);
                boolean isFollowingMe = followRepository.existsByFollowerAndFollowed(targetUser, currentUser);
                isFriend = isFollower && isFollowingMe;
            }
        }

        List<Post> posts = postRepository.findUserPostsWithVisibility(
                targetUsername, isOwner, isFollower, isFriend);

        Map<Long, Relation> cache = new HashMap<>();
        return posts.stream()
                .map(post -> toPostResponse(post, currentUser, cache))
                .toList();
    }

    // =====================================================================
    // HELPER
    // =====================================================================

    private User findUserOrNull(String email) {
        if (email == null || email.isBlank()) return null;
        return userRepository.findByEmail(email).orElse(null);
    }

    private User requireActiveUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng."));
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalStateException("Tài khoản hiện không hoạt động.");
        }
        return user;
    }

    /** Bài phải tồn tại, chưa bị xóa và thuộc về user. */
    private Post requireOwnedPublishedPost(User user, Long postId, String action) {
        Post post = postRepository.findByIdAndStatus(postId, STATUS_PUBLISHED)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy bài viết."));
        if (!post.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("Bạn không có quyền " + action + " bài viết này.");
        }
        return post;
    }

    private String normalizeVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) return "PUBLIC";
        String value = visibility.trim().toUpperCase();
        if (!VISIBILITIES.contains(value)) {
            throw new IllegalArgumentException("Quyền hiển thị không hợp lệ.");
        }
        return value;
    }

    /** Tính quan hệ người xem - tác giả, mỗi tác giả chỉ truy vấn một lần. */
    private Relation resolveRelation(User viewer, User author, Map<Long, Relation> cache) {
        if (viewer == null) {
            return new Relation(false, false, "NONE");
        }
        return cache.computeIfAbsent(author.getId(), authorId -> {
            if (viewer.getId().equals(authorId)) {
                return new Relation(false, false, "SELF");
            }
            boolean followedByMe = followRepository.existsByFollowerAndFollowed(viewer, author);
            boolean followingMe = followRepository.existsByFollowerAndFollowed(author, viewer);

            String label;
            if (followedByMe && followingMe) label = "FRIENDS";
            else if (followedByMe) label = "FOLLOWING";
            else if (followingMe) label = "FOLLOWED_BY";
            else label = "NONE";

            return new Relation(followedByMe, followingMe, label);
        });
    }

    private PostResponse toPostResponse(Post post, User currentUser, Map<Long, Relation> cache) {

        PostResponse dto = new PostResponse();
        dto.setId(post.getId());

        User author = post.getUser();
        dto.setAuthorName(author.getFullName());
        dto.setAuthorUsername(author.getUsername());
        dto.setAuthorAvatar(author.getAvatar());
        dto.setVisibility(post.getVisibility());

        Relation relation = resolveRelation(currentUser, author, cache);
        dto.setFollowedByMe(relation.followedByMe());
        dto.setFollowingMe(relation.followingMe());
        dto.setRelationship(relation.label());

        dto.setCaption(post.getCaption());
        dto.setLocationName(post.getLocationName());
        dto.setLatitude(post.getLatitude() != null ? post.getLatitude().doubleValue() : null);
        dto.setLongitude(post.getLongitude() != null ? post.getLongitude().doubleValue() : null);
        dto.setRating(post.getRating());
        dto.setCreatedAt(post.getCreatedAt());

        List<Image> mediaFiles = imageRepository.findByEntityTypeAndEntityId("POST", post.getId());

        dto.setImageUrls(mediaFiles.stream()
                .filter(i -> "IMAGE".equalsIgnoreCase(i.getMediaType()))
                .map(Image::getImageUrl)
                .toList());

        dto.setVideoUrls(mediaFiles.stream()
                .filter(i -> "VIDEO".equalsIgnoreCase(i.getMediaType()))
                .map(Image::getImageUrl)
                .toList());

        return dto;
    }

    private void validatePostData(Double latitude, Double longitude, Integer rating) {

        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("Latitude và longitude phải được nhập cùng nhau.");
        }
        if (latitude != null && (latitude < -90 || latitude > 90)) {
            throw new IllegalArgumentException("Latitude không hợp lệ.");
        }
        if (longitude != null && (longitude < -180 || longitude > 180)) {
            throw new IllegalArgumentException("Longitude không hợp lệ.");
        }
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("Rating phải nằm trong khoảng từ 1 đến 5.");
        }
    }
}