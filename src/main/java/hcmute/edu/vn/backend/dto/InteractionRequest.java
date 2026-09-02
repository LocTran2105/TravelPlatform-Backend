package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InteractionRequest {
    private String targetType; // Ví dụ: "TRIP", "HOTEL", "CUSTOM_PLACE"
    private Long targetId;     // ID của bài viết hoặc địa điểm
    private String platform;   // Chỉ dùng cho chức năng Share (VD: "FACEBOOK")
}