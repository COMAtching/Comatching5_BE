package com.comatching.common.dto.chat;

import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Ordered copy of the visible conversation at report submission. */
public record ReportChatEvidence(Range range, List<Message> messages) {
    public record Range(@NotBlank @Size(max = 128) String roomId,
                        @NotBlank @Size(max = 128) String firstMessageId,
                        @NotBlank @Size(max = 128) String lastMessageId) {}
    public enum Part { BEFORE, SELECTED, AFTER }
    public record Message(String id, long senderId, String type, String content, String createdAt, Part part) {}
}
