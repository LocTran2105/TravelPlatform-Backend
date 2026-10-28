package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class PostResponse {
    private Long id;
    private String authorName;
    private String authorAvatar;
    private String caption;
    private String locationName;
    private Double latitude;
    private Double longitude;
    private Integer rating;
    private LocalDateTime createdAt;

    // Tách riêng danh sách link Ảnh và Video để React dễ render (dùng <img> hay <video>)
    private List<String> imageUrls;
    private List<String> videoUrls;
}