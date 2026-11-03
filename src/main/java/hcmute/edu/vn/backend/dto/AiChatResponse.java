package hcmute.edu.vn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponse {
    private Long conversationId;
    private String assistantMessage;
    private String phase;
    private List<PlaceDiscoveryDTO> recommendations;
    private boolean requiresConfirmation;
    private List<MessageHistoryDTO> history;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageHistoryDTO {
        private Long id;
        private String sender; // "user" hoặc "ai"
        private String text;
        private List<PlaceDiscoveryDTO> recommendations;
    }
}