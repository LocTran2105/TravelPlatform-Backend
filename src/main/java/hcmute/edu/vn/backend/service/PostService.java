package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PostPageResponse;
import hcmute.edu.vn.backend.dto.PostResponse;
import hcmute.edu.vn.backend.dto.PostTripDtos.CopyTripRequest;
import hcmute.edu.vn.backend.dto.PostTripDtos.CopyTripResponse;
import hcmute.edu.vn.backend.dto.PostTripDtos.ItineraryPreview;
import hcmute.edu.vn.backend.dto.PostTripDtos.TripPreview;
import hcmute.edu.vn.backend.dto.TripSnapshot;
import hcmute.edu.vn.backend.entity.Image;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
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

    // Lịch trình trong bài (bản chụp): chụp, sao chép, quyền xem bài.
    private final TripRepository tripRepository;
    private final TripSnapshotService tripSnapshotService;
    private final TripCloneService tripCloneService;
    private final PostAccessService postAccessService;

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
            Long tripId,
            List<MultipartFile> files
    ) throws Exception {

        User user = requireActiveUser(email);

        validatePostData(latitude, longitude, rating);
        String normalizedVisibility = normalizeVisibility(visibility);
        Trip attachedTrip = resolveAttachableTrip(user, tripId);
        TripSnapshot snapshot = attachedTrip != null ? tripSnapshotService.capture(attachedTrip) : null;

        // Bỏ các phần tử rỗng (ô chọn file trống) trước khi kiểm tra.
        List<MultipartFile> media = files == null
                ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();

        boolean hasCaption = caption != null && !caption.isBlank();
        if (!hasCaption && media.isEmpty() && attachedTrip == null) {
            throw new IllegalArgumentException("Bài viết cần có nội dung, ảnh/video hoặc lịch trình.");
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
        if (attachedTrip != null) {
            applySnapshot(post, attachedTrip, snapshot);
        }
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
        if ("HIDDEN".equals(post.getModerationStatus())) {
            throw new IllegalStateException("Bài viết đã bị kiểm duyệt viên ẩn nên không thể chỉnh sửa. Bạn vẫn có thể xóa bài.");
        }

        if (caption != null) {
            post.setCaption(caption.trim());
        }
        if (visibility != null && !visibility.isBlank()) {
            post.setVisibility(normalizeVisibility(visibility));
        }

        boolean hasCaption = post.getCaption() != null && !post.getCaption().isBlank();
        boolean hasMedia = !imageRepository
                .findByEntityTypeAndEntityId("POST", post.getId()).isEmpty();
        if (!hasCaption && !hasMedia && post.getTripSnapshot() == null) {
            throw new IllegalArgumentException("Bài viết cần có nội dung, ảnh/video hoặc lịch trình.");
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
    // LỊCH TRÌNH TRONG BÀI (BẢN CHỤP): XEM + SAO CHÉP + CẬP NHẬT + GỠ
    //  - Bài lưu bản chụp lịch trình + chi phí lúc đăng; tác giả sửa chuyến gốc thì bài không đổi.
    //  - Ai xem được BÀI VIẾT thì xem và sao chép được (quyền của chuyến đi gốc không áp dụng).
    //  - Chỉ tác giả được cập nhật bản chụp từ chuyến gốc hoặc gỡ lịch trình khỏi bài.
    // =====================================================================

    /** Lịch trình đầy đủ (chỉ đọc) của bài viết. viewerEmail = null nghĩa là khách. */
    @Transactional(readOnly = true)
    public ItineraryPreview getPostItinerary(Long postId, String viewerEmail) {
        User viewer = findUserOrNull(viewerEmail);
        Post post = requireViewablePostWithTrip(postId, viewer);
        TripPreview preview = buildTripPreviews(List.of(post), viewer).get(post.getId());
        return tripSnapshotService.toItinerary(post.getTripSnapshot(), preview);
    }

    /** Sao chép lịch trình trong bài (từ bản chụp) thành chuyến đi mới (Riêng tư) của người đang đăng nhập. */
    @Transactional
    public CopyTripResponse copyTripFromPost(String email, Long postId, CopyTripRequest request) {
        User user = requireActiveUser(email);
        Post post = requireViewablePostWithTrip(postId, user);
        Trip copy = tripCloneService.copyFromSnapshot(
                post,
                user,
                request != null ? request.title() : null,
                request != null ? request.startDate() : null);
        return new CopyTripResponse(copy.getId(), copy.getTitle());
    }

    /** Tác giả chụp lại lịch trình từ chuyến đi gốc hiện tại. */
    @Transactional
    public PostResponse refreshPostTrip(String email, Long postId) {
        User user = requireActiveUser(email);
        Post post = requireOwnedPublishedPost(user, postId, "cập nhật lịch trình của");
        requireNotHidden(post);
        if (post.getTripSnapshot() == null) {
            throw new IllegalStateException("Bài viết không có lịch trình.");
        }
        Trip trip = post.getTrip();
        if (trip == null) {
            throw new IllegalStateException(
                    "Chuyến đi gốc đã bị xóa nên không thể cập nhật. Lịch trình trong bài vẫn được giữ nguyên.");
        }
        if (!trip.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("Bạn không còn là chủ của chuyến đi này.");
        }
        applySnapshot(post, trip, tripSnapshotService.capture(trip));
        Post saved = postRepository.save(post);
        return toPostResponses(List.of(saved), user).get(0);
    }

    /** Tác giả gỡ lịch trình khỏi bài (bản chụp bị xóa; các bản đã sao chép không bị ảnh hưởng). */
    @Transactional
    public PostResponse removePostTrip(String email, Long postId) {
        User user = requireActiveUser(email);
        Post post = requireOwnedPublishedPost(user, postId, "gỡ lịch trình của");
        requireNotHidden(post);
        if (post.getTripSnapshot() != null || post.getTrip() != null) {
            boolean hasCaption = post.getCaption() != null && !post.getCaption().isBlank();
            boolean hasMedia = !imageRepository.findByEntityTypeAndEntityId("POST", post.getId()).isEmpty();
            if (!hasCaption && !hasMedia) {
                throw new IllegalStateException(
                        "Bài viết chỉ có lịch trình. Hãy thêm nội dung trước khi gỡ, hoặc xóa bài viết.");
            }
            post.setTrip(null);
            post.setTripSnapshot(null);
            post.setTripSnapshotAt(null);
            post.setTripSnapshotHash(null);
            post = postRepository.save(post);
        }
        return toPostResponses(List.of(post), user).get(0);
    }

    /**
     * Tạo bản chụp cho bài đã gắn chuyến đi từ trước khi có tính năng bản chụp.
     * Gọi từ PostTripSnapshotBackfill; trả về true nếu đã tạo.
     */
    @Transactional
    public boolean backfillTripSnapshot(Long postId) {
        Post post = postRepository.findById(postId).orElse(null);
        if (post == null || post.getTrip() == null || post.getTripSnapshot() != null) {
            return false;
        }
        applySnapshot(post, post.getTrip(), tripSnapshotService.capture(post.getTrip()));
        postRepository.save(post);
        return true;
    }

    private void applySnapshot(Post post, Trip trip, TripSnapshot snapshot) {
        post.setTrip(trip);
        post.setTripSnapshot(snapshot);
        post.setTripSnapshotAt(LocalDateTime.now(ZoneOffset.UTC));
        post.setTripSnapshotHash(tripSnapshotService.hash(snapshot));
    }

    private void requireNotHidden(Post post) {
        if ("HIDDEN".equals(post.getModerationStatus())) {
            throw new IllegalStateException(
                    "Bài viết đã bị kiểm duyệt viên ẩn nên không thể chỉnh sửa. Bạn vẫn có thể xóa bài.");
        }
    }

    /** 404 nếu không xem được bài hoặc bài không có lịch trình. */
    private Post requireViewablePostWithTrip(Long postId, User viewer) {
        Post post = postAccessService.requireViewablePost(postId, viewer);
        if (post.getTripSnapshot() == null) {
            throw new NoSuchElementException("Bài viết không có lịch trình.");
        }
        return post;
    }

    /** Chuyến đi được gắn vào bài: chỉ cần là CHỦ chuyến đi (quyền hiển thị nào cũng được). */
    private Trip resolveAttachableTrip(User user, Long tripId) {
        if (tripId == null) return null;
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến đi để gắn vào bài viết."));
        if (!trip.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn chỉ có thể gắn chuyến đi do chính mình tạo.");
        }
        return trip;
    }

    /**
     * Thẻ lịch trình cho cả trang bài, dựng từ BẢN CHỤP (không đọc chuyến gốc).
     * Lượt sao chép: 1 câu SQL. Bài của chính người xem: so bản chụp với chuyến gốc hiện tại
     * (chụp gộp các chuyến gốc với số câu SQL cố định) để báo "lịch trình gốc đã thay đổi".
     */
    private Map<Long, TripPreview> buildTripPreviews(List<Post> posts, User viewer) {
        List<Post> withTrip = posts.stream().filter(p -> p.getTripSnapshot() != null).toList();
        if (withTrip.isEmpty()) return Map.of();

        List<Long> postIds = withTrip.stream().map(Post::getId).toList();
        Map<Long, Long> copyCounts = toCountMap(tripRepository.countCopiesByPostIds(postIds));

        List<Post> own = viewer == null ? List.of() : withTrip.stream()
                .filter(p -> p.getUser().getId().equals(viewer.getId()))
                .toList();
        Set<Long> sourceIds = own.stream()
                .map(Post::getTrip)
                .filter(Objects::nonNull)
                .map(Trip::getId)
                .collect(Collectors.toSet());
        Map<Long, String> liveHashByTrip = new HashMap<>();
        if (!sourceIds.isEmpty()) {
            List<Trip> sources = tripRepository.findAllWithOwnerByIdIn(sourceIds);
            try {
                tripSnapshotService.captureAll(sources)
                        .forEach((id, snap) -> liveHashByTrip.put(id, tripSnapshotService.hash(snap)));
            } catch (IllegalArgumentException tooLarge) {
                // Chuyến gốc đã vượt giới hạn: chắc chắn khác bản chụp.
                sources.forEach(t -> liveHashByTrip.put(t.getId(), ""));
            }
        }

        Map<Long, TripPreview> result = new HashMap<>();
        for (Post post : withTrip) {
            Boolean sourceAvailable = null;
            Boolean sourceChanged = null;
            if (own.contains(post)) {
                Trip source = post.getTrip();
                sourceAvailable = source != null;
                sourceChanged = source != null
                        && !Objects.equals(liveHashByTrip.get(source.getId()), post.getTripSnapshotHash());
            }
            result.put(post.getId(), tripSnapshotService.toPreview(
                    post.getTripSnapshot(),
                    post.getTripSnapshotAt(),
                    copyCounts.getOrDefault(post.getId(), 0L),
                    sourceAvailable,
                    sourceChanged));
        }
        return result;
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

        // Lịch trình trong bài: dựng từ bản chụp của từng bài.
        Map<Long, TripPreview> tripPreviews = buildTripPreviews(posts, currentUser);

        List<PostResponse> result = new ArrayList<>(posts.size());
        for (Post post : posts) {
            PostResponse dto = new PostResponse();
            dto.setId(post.getId());

            User author = post.getUser();
            dto.setAuthorName(author.getFullName());
            dto.setAuthorUsername(author.getUsername());
            dto.setAuthorAvatar(author.getAvatar());
            dto.setVisibility(post.getVisibility());

            // Chỉ tác giả mới biết bài của mình bị ẩn và lý do.
            if (currentUser != null && currentUser.getId().equals(author.getId())) {
                dto.setModerationStatus(post.getModerationStatus());
                if ("HIDDEN".equals(post.getModerationStatus())) {
                    dto.setModerationNote(post.getModerationNote());
                }
            }

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

            dto.setTrip(tripPreviews.get(post.getId()));

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