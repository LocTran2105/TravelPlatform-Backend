package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PostResponse;
import hcmute.edu.vn.backend.entity.Image;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.ImageRepository;
import hcmute.edu.vn.backend.repository.PostRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class PostService {

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ImageRepository imageRepository;

    @Autowired
    private CloudinaryService cloudinaryService;

    @Transactional
    public Post createPost(String email, String caption, String locationName,
                           Double lat, Double lng, Integer rating,
                           List<MultipartFile> files) throws Exception {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new Exception("Không tìm thấy người dùng!"));

        // 1. Khởi tạo và lưu bài đăng (Post)
        Post post = new Post();
        post.setUser(user);
        post.setCaption(caption);
        post.setLocationName(locationName);
        post.setLatitude(lat);
        post.setLongitude(lng);
        post.setRating(rating);

        Post savedPost = postRepository.save(post);

        // 2. Xử lý upload file (nếu có)
        if (files != null && !files.isEmpty()) {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                if (!file.isEmpty()) {
                    // Upload lên Cloudinary vào thư mục "social_posts"
                    String secureUrl = cloudinaryService.uploadFile(file, "social_posts");

                    // Nhận diện loại file (IMAGE hoặc VIDEO) dựa vào ContentType
                    String contentType = file.getContentType();
                    String mediaType = (contentType != null && contentType.startsWith("video/")) ? "VIDEO" : "IMAGE";

                    // Lưu đường link vào bảng images
                    Image image = new Image();
                    image.setEntityType("POST");
                    image.setEntityId(savedPost.getId());
                    image.setImageUrl(secureUrl);
                    image.setMediaType(mediaType);
                    image.setIsCover(i == 0); // File đầu tiên auto làm ảnh bìa

                    imageRepository.save(image);
                }
            }
        }

        return savedPost;
    }

    // Hàm lấy danh sách tất cả bài đăng kèm theo ảnh/video
    public List<PostResponse> getAllPosts() {
        List<Post> posts = postRepository.findAllByOrderByCreatedAtDesc();

        return posts.stream().map(post -> {
            PostResponse dto = new PostResponse();
            dto.setId(post.getId());
            dto.setAuthorName(post.getUser().getFullName());
            dto.setAuthorAvatar(post.getUser().getAvatar());
            dto.setCaption(post.getCaption());
            dto.setLocationName(post.getLocationName());
            dto.setLatitude(post.getLatitude());
            dto.setLongitude(post.getLongitude());
            dto.setRating(post.getRating());
            dto.setCreatedAt(post.getCreatedAt());

            // Tìm tất cả file media của bài viết này trong bảng images
            List<Image> mediaFiles = imageRepository.findByEntityTypeAndEntityId("POST", post.getId());

            // Phân loại Ảnh và Video
            List<String> images = mediaFiles.stream()
                    .filter(m -> "IMAGE".equals(m.getMediaType()))
                    .map(Image::getImageUrl)
                    .toList();

            List<String> videos = mediaFiles.stream()
                    .filter(m -> "VIDEO".equals(m.getMediaType()))
                    .map(Image::getImageUrl)
                    .toList();

            dto.setImageUrls(images);
            dto.setVideoUrls(videos);

            return dto;
        }).toList();
    }
}