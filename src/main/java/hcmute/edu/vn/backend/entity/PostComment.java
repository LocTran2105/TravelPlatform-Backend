package hcmute.edu.vn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "post_comments")
@Getter
@Setter
public class PostComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Liên kết với bài đăng
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    private Post post;

    // Người viết bình luận
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Cách 1: Sử dụng kiểu Long nguyên thủy để tránh lỗi StackOverflow và tối ưu truy vấn
    @Column(name = "parent_id")
    private Long parentId;

    // Nội dung bình luận
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    // Kiểm duyệt: PUBLISHED (hiển thị) hoặc HIDDEN (bị kiểm duyệt viên ẩn)
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PUBLISHED";

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "moderated_by")
    private User moderatedBy;

    @Column(name = "moderation_note", length = 500)
    private String moderationNote;

    @Column(name = "moderated_at")
    private LocalDateTime moderatedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}