package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.PartnerApplicationRequest;
import hcmute.edu.vn.backend.service.PartnerApplicationService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/partner-applications")
@RequiredArgsConstructor
public class PartnerApplicationController {

    private final PartnerApplicationService applicationService;

    /**
     * Traveller đăng ký trở thành Partner.
     */
    @PostMapping
    public ResponseEntity<?> apply(
            @RequestBody PartnerApplicationRequest request,
            Principal principal
    ) {
        try {

            return ResponseEntity.ok(
                    applicationService.apply(
                            request,
                            principal.getName()
                    )
            );

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }

    /**
     * Xem lịch sử đăng ký của mình.
     */
    @GetMapping("/my-applications")
    public ResponseEntity<?> getMyApplications(
            Principal principal
    ) {

        try {

            return ResponseEntity.ok(
                    applicationService.getMyApplications(
                            principal.getName()
                    )
            );

        } catch (IllegalArgumentException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }
}