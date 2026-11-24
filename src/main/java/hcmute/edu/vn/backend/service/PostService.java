package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PostPageResponse;
import hcmute.edu.vn.backend.dto.PostResponse;
import hcmute.edu.vn.backend.entity.Image;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostService {

    private static final Set<String> VISIBILITIES =
            Set.of("PUBLIC", "FOLLOWERS", "FRIENDS", "PRIVATE");

    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_DELETED = "DELETED";

    /** Số ảnh/video tối đa cho một bài (frontend giới hạn giống vậy). */
    private static final int MAX_MEDIA_FILES = 10;

    /** Phân trang: số bài mỗi trang mặc định / tối đa. */
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 30;

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final CloudinaryService cloudinaryService;
    private final FollowRepository followRepository;

    // Thống kê gộp: số tim, đã tim chưa, số bình luận, ảnh/video cho cả danh sách bài.
    private final PostStatsRepository postStatsRepository;

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

        // Bỏ các phần tử rỗng (ô chọn file trống) trước khi kiểm tra.
        List<MultipartFile> media = files == null
                ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();

        boolean hasCaption = caption != null && !caption.isBlank();
        if (!hasCaption && media.isEmpty()) {
            throw new IllegalArgumentException("Bài viết cần có nội dung hoặc ảnh/video.");
        }
        if (media.size() > MAX_MEDIA_FILES) {
            throw new IllegalArgumentException(
                    "Mỗi bài chỉ được đăng tối đa " + MAX_MEDIA_FILES + " ảnh/video.");
        }

        // Kiểm tra loại file TRƯỚC khi upload / lưu bài để không bị dở dang.
        for (MultipartFile file : media) {
            String contentType = file.getContentType();
            if (contentType == null
                    || (!contentType.startsWith("image/") && !contentType.startsWith("video/"))) {
                throw new IllegalArgumentException("Chỉ hỗ trợ upload ảnh và video.");
            }
        }

        // Upload lên Cloudinary TRƯỚC khi ghi DB: nếu một file lỗi thì chưa có
        // bài nào được tạo (trước đây bài đã lưu rồi mới upload, lỗi giữa chừng
        // làm mất ảnh sau). Giữ nguyên thứ tự người dùng chọn.
        List<String> uploadedUrls = new ArrayList<>();
        List<String> uploadedTypes = new ArrayList<>();
        for (MultipartFile file : media) {
            uploadedUrls.add(cloudinaryService.uploadFile(file, "social_posts"));
            uploadedTypes.add(file.getContentType().startsWith("video/") ? "VIDEO" : "IMAGE");
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

        // Lưu từng ảnh/video theo đúng thứ tự (id tăng dần = thứ tự hiển thị).
        List<Image> images = new ArrayList<>();
        for (int i = 0; i < uploadedUrls.size(); i++) {
            Image image = new Image();
            image.setEntityType("POST");
            image.setEntityId(savedPost.getId());
            image.setImageUrl(uploadedUrls.get(i));
            image.setMediaType(uploadedTypes.get(i));
            image.setIsCover(i == 0);
            images.add(image);
        }
        imageRepository.saveAll(images);

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
        return toPostResponses(List.of(saved), user).get(0);
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

        return toPostResponses(posts, currentUser);
    }

    /**
     * Bảng tin theo TRANG (con trỏ). before = id bài cuối của trang trước
     * (null = trang đầu). Trả về size bài + hasMore + nextCursor.
     */
    @Transactional(readOnly = true)
    public PostPageResponse getFeedPage(String currentUserEmail, Long before, Integer size) {
        User currentUser = findUserOrNull(currentUserEmail);
        Long viewerId = currentUser != null ? currentUser.getId() : -1L;
        int pageSize = normalizePageSize(size);

        // Lấy dư 1 bài để biết còn trang sau hay không, không cần câu COUNT.
        List<Post> rows = postRepository.findFeedPageForViewer(
                viewerId, normalizeCursor(before), PageRequest.of(0, pageSize + 1));

        return buildPage(rows, pageSize, currentUser);
    }

    /** Bài viết của một người, có áp dụng quyền hiển thị (không phân trang, giữ cho nơi gọi cũ). */
    @Transactional(readOnly = true)
    public List<PostResponse> getUserPosts(String targetUsername, String currentUserEmail) {
        User currentUser = findUserOrNull(currentUserEmail);
        ProfileAccess access = resolveProfileAccess(targetUsername, currentUser);

        List<Post> posts = postRepository.findUserPostsWithVisibility(
                targetUsername, access.isOwner(), access.isFollower(), access.isFriend());

        return toPostResponses(posts, currentUser);
    }

    /** Bài viết của một người, phân trang theo con trỏ. */
    @Transactional(readOnly = true)
    public PostPageResponse getUserPostsPage(
            String targetUsername, String currentUserEmail, Long before, Integer size) {
        User currentUser = findUserOrNull(currentUserEmail);
        ProfileAccess access = resolveProfileAccess(targetUsername, currentUser);
        int pageSize = normalizePageSize(size);

        List<Post> rows = postRepository.findUserPostsPage(
                targetUsername, access.isOwner(), access.isFollower(), access.isFriend(),
                normalizeCursor(before), PageRequest.of(0, pageSize + 1));

        return buildPage(rows, pageSize, currentUser);
    }

    // =====================================================================
    // HELPER
    // =====================================================================

    private record ProfileAccess(boolean isOwner, boolean isFollower, boolean isFriend) {}

    /** Người xem là chủ trang / người theo dõi / bạn bè (follow chéo 2 chiều) của targetUsername. */
    private ProfileAccess resolveProfileAccess(String targetUsername, User currentUser) {
        User targetUser = userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng."));

        if (currentUser == null) {
            return new ProfileAccess(false, false, false);
        }
        if (currentUser.getId().equals(targetUser.getId())) {
            return new ProfileAccess(true, false, false);
        }
        boolean isFollower = followRepository.existsByFollowerAndFollowed(currentUser, targetUser);
        boolean isFollowingMe = followRepository.existsByFollowerAndFollowed(targetUser, currentUser);
        return new ProfileAccess(false, isFollower, isFollower && isFollowingMe);
    }

    private int normalizePageSize(Integer size) {
        return clampPageSize(size);
    }

    /** size rỗng / sai -> mặc định; quá lớn -> MAX_PAGE_SIZE. */
    public static int clampPageSize(Integer size) {
        if (size == null || size < 1) return DEFAULT_PAGE_SIZE;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private Long normalizeCursor(Long before) {
        return (before == null || before < 1) ? Long.MAX_VALUE : before;
    }

    /** rows có tối đa pageSize + 1 phần tử; phần tử dư chỉ để biết còn trang sau. */
    private PostPageResponse buildPage(List<Post> rows, int pageSize, User currentUser) {
        boolean hasMore = rows.size() > pageSize;
        List<Post> pagePosts = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? String.valueOf(pagePosts.get(pagePosts.size() - 1).getId()) : null;
        return new PostPageResponse(toPostResponses(pagePosts, currentUser), hasMore, nextCursor);
    }

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

    /**
     * Chuyển cả danh sách bài sang DTO. Số tim, trạng thái đã tim, số bình luận
     * và ảnh/video được lấy GỘP cho cả danh sách (4 câu SQL) thay vì 3 câu cho
     * MỖI bài như trước.
     */
    public List<PostResponse> toResponses(List<Post> posts, User currentUser) {
        return toPostResponses(posts, currentUser);
    }

    private List<PostResponse> toPostResponses(List<Post> posts, User currentUser) {
        if (posts.isEmpty()) {
            return List.of();
        }

        List<Long> postIds = posts.stream().map(Post::getId).toList();

        Map<Long, Long> likeCounts = toCountMap(postStatsRepository.countLikesByPostIds(postIds));
        Map<Long, Long> commentCounts = toCountMap(postStatsRepository.countCommentsByPostIds(postIds));
        Set<Long> likedByMe = currentUser == null
                ? Set.of()
                : new HashSet<>(postStatsRepository.findLikedPostIds(currentUser.getId(), postIds));
        Map<Long, List<Image>> mediaByPost = postStatsRepository.findMediaByPostIds(postIds)
                .stream()
                .collect(Collectors.groupingBy(Image::getEntityId, LinkedHashMap::new, Collectors.toList()));

        Map<Long, Relation> relationCache = new HashMap<>();

        List<PostResponse> result = new ArrayList<>(posts.size());
        for (Post post : posts) {
            PostResponse dto = new PostResponse();
            dto.setId(post.getId());

            User author = post.getUser();
            dto.setAuthorName(author.getFullName());
            dto.setAuthorUsername(author.getUsername());
            dto.setAuthorAvatar(author.getAvatar());
            dto.setVisibility(post.getVisibility());

            Relation relation = resolveRelation(currentUser, author, relationCache);
            dto.setFollowedByMe(relation.followedByMe());
            dto.setFollowingMe(relation.followingMe());
            dto.setRelationship(relation.label());

            dto.setCaption(post.getCaption());
            dto.setLocationName(post.getLocationName());
            dto.setLatitude(post.getLatitude() != null ? post.getLatitude().doubleValue() : null);
            dto.setLongitude(post.getLongitude() != null ? post.getLongitude().doubleValue() : null);
            dto.setRating(post.getRating());
            dto.setCreatedAt(post.getCreatedAt());

            dto.setLikeCount(likeCounts.getOrDefault(post.getId(), 0L));
            dto.setCommentCount(commentCounts.getOrDefault(post.getId(), 0L));
            dto.setLikedByMe(likedByMe.contains(post.getId()));

            List<Image> mediaFiles = mediaByPost.getOrDefault(post.getId(), List.of());

            dto.setMedia(mediaFiles.stream()
                    .map(i -> new PostResponse.MediaItem(i.getImageUrl(), normalizeMediaType(i)))
                    .toList());
            dto.setImageUrls(mediaFiles.stream()
                    .filter(i -> "IMAGE".equals(normalizeMediaType(i)))
                    .map(Image::getImageUrl)
                    .toList());
            dto.setVideoUrls(mediaFiles.stream()
                    .filter(i -> "VIDEO".equals(normalizeMediaType(i)))
                    .map(Image::getImageUrl)
                    .toList());

            result.add(dto);
        }
        return result;
    }

    /** [id, count] -> Map; COUNT có thể là Long hoặc BigInteger tùy driver. */
    private Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return map;
    }

    private String normalizeMediaType(Image image) {
        return "VIDEO".equalsIgnoreCase(image.getMediaType()) ? "VIDEO" : "IMAGE";
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