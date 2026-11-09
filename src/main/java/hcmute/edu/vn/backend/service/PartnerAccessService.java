package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.PartnerProfile;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.PartnerProfileRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Centralizes partner identity and approval checks.
 * A partner profile is usable only after an administrator has approved it
 * and the corresponding user has ROLE_PARTNER.
 */
@Service
@RequiredArgsConstructor
public class PartnerAccessService {

    private final UserRepository userRepository;
    private final PartnerProfileRepository partnerProfileRepository;

    public PartnerProfile requireApprovedPartner(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản."));

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalStateException("Tài khoản hiện không hoạt động.");
        }

        if (!"ROLE_PARTNER".equalsIgnoreCase(user.getRole())) {
            throw new IllegalStateException("Chỉ tài khoản đối tác mới được thực hiện thao tác này.");
        }

        PartnerProfile profile = partnerProfileRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new IllegalStateException("Tài khoản chưa có hồ sơ đối tác."));

        String verificationStatus = profile.getVerificationStatus();
        if (verificationStatus == null
                || !"APPROVED".equals(verificationStatus.toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("Hồ sơ đối tác chưa được quản trị viên phê duyệt.");
        }

        return profile;
    }
}
