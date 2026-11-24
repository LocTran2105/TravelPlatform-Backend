package hcmute.edu.vn.backend.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserMinimalDTO {
    private Long id;
    private String username;
    private String fullName;
    private String avatar;
    // Bổ sung trạng thái để biết user hiện tại có đang follow người này không (dùng cho nút bấm trên UI)
    private boolean FollowedByMe;
    private boolean FollowingMe;  // Người này có đang theo dõi TÔI không
}