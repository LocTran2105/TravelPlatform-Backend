package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.RoomRequest;
import hcmute.edu.vn.backend.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/partner/businesses/{businessId}/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    /**
     * Lấy danh sách phòng.
     */
    @GetMapping
    public ResponseEntity<?> getRooms(
            @PathVariable Long businessId,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    roomService.getBusinessRooms(
                            businessId,
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
     * Thêm phòng.
     */
    @PostMapping
    public ResponseEntity<?> addRoom(
            @PathVariable Long businessId,
            @Valid @RequestBody RoomRequest request,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    roomService.addRoom(
                            businessId,
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
     * Cập nhật phòng.
     */
    @PutMapping("/{roomId}")
    public ResponseEntity<?> updateRoom(
            @PathVariable Long businessId,
            @PathVariable Long roomId,
            @Valid @RequestBody RoomRequest request,
            Principal principal
    ) {
        try {
            return ResponseEntity.ok(
                    roomService.updateRoom(
                            businessId,
                            roomId,
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
     * Ngừng kinh doanh phòng.
     */
    @DeleteMapping("/{roomId}")
    public ResponseEntity<?> deactivateRoom(
            @PathVariable Long businessId,
            @PathVariable Long roomId,
            Principal principal
    ) {
        try {
            roomService.deactivateRoom(
                    businessId,
                    roomId,
                    principal.getName()
            );

            return ResponseEntity.ok(
                    "Đã ngừng kinh doanh phòng. "
                            + "Lịch sử booking được giữ nguyên."
            );

        } catch (IllegalArgumentException
                 | IllegalStateException exception) {

            return ResponseEntity.badRequest()
                    .body(exception.getMessage());
        }
    }
}