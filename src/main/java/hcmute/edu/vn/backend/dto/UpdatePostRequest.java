package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;

/** Body của PUT /api/posts/{id}. Trường nào null thì giữ nguyên giá trị cũ. */
@Getter
@Setter
public class UpdatePostRequest {
    private String caption;
    private String visibility; // PUBLIC, FOLLOWERS, FRIENDS, PRIVATE
}