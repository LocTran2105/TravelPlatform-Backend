package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.AiChatRequest;
import hcmute.edu.vn.backend.service.GeminiAiService;
import hcmute.edu.vn.backend.service.TripAccessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class AiChatController {

    private final GeminiAiService geminiAiService;

    @GetMapping("/{tripId}/ai/history")
    public ResponseEntity<?> getChatHistory(@PathVariable Long tripId) {
        try {
            return ResponseEntity.ok(geminiAiService.getConversationHistory(tripId));
        } catch (TripAccessException e) {
            return ResponseEntity.status(e.getStatus()).body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/{tripId}/ai/chat")
    public ResponseEntity<?> chatWithAi(@PathVariable Long tripId, @RequestBody AiChatRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body("Thiếu nội dung tin nhắn.");
        }
        // tripId trên URL là nguồn duy nhất; bỏ qua tripId client gửi trong body.
        request.setTripId(tripId);
        try {
            return ResponseEntity.ok(geminiAiService.processChat(request));
        } catch (TripAccessException e) {
            return ResponseEntity.status(e.getStatus()).body(e.getMessage());
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}