
package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.dto.AiChatRequest;
import hcmute.edu.vn.backend.dto.AiChatResponse;
import hcmute.edu.vn.backend.service.GeminiAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class AiChatController {

    private final GeminiAiService geminiAiService;

    @GetMapping("/{tripId}/ai/history")
    public ResponseEntity<AiChatResponse> getChatHistory(
            @PathVariable Long tripId) {

        return ResponseEntity.ok(
                geminiAiService.getConversationHistory(tripId)
        );
    }

    @PostMapping("/{tripId}/ai/chat")
    public ResponseEntity<AiChatResponse> chatWithAi(
            @PathVariable Long tripId,
            @RequestBody AiChatRequest request) {

        request.setTripId(tripId);

        return ResponseEntity.ok(
                geminiAiService.processChat(request)
        );
    }
}
