package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CommentRequest {
    private String content;
    private Long parentId; // ID của comment cha (nếu đây là một lượt reply)
}