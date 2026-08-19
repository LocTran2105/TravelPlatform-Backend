package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequest {
    private String email;
    private String phoneNumber;
    private String password;
    private String fullName;
}