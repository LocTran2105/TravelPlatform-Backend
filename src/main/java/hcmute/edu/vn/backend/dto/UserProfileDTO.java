package hcmute.edu.vn.backend.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserProfileDTO {
    private Long id;
    private String email;
    private String fullName;
    private String phoneNumber;
    private String username;
    private String avatar;
    private String role;
}