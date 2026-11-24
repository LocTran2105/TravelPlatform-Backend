package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Ảnh bìa có sẵn trong thư viện của hệ thống (URL Cloudinary do quản trị thêm vào bảng cover_images). */
@Entity
@Table(name = "cover_images")
@Getter
@Setter
@NoArgsConstructor
public class CoverImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    /** Từ khoá điểm đến, cách nhau bởi dấu phẩy (VD: "da lat,dalat,lam dong"). Dùng để gợi ý ảnh theo điểm đến. */
    @Column(length = 500)
    private String keywords;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
