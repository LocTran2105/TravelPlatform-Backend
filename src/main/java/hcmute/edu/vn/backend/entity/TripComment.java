package hcmute.edu.vn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "trip_comments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TripComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Dùng @JsonIgnore để tránh vòng lặp đệ quy vô hạn khi parse JSON
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Bình luận cha (áp dụng khi người dùng bấm Reply)
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private TripComment parentComment;

    // Danh sách các câu trả lời (Replies) của bình luận này
    @OneToMany(mappedBy = "parentComment", cascade = CascadeType.ALL)
    private List<TripComment> replies;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}