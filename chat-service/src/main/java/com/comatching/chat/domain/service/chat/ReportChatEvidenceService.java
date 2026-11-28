package com.comatching.chat.domain.service.chat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.comatching.chat.domain.entity.ChatMessage;
import com.comatching.chat.domain.enums.MessageType;
import com.comatching.chat.domain.repository.ChatMessageRepository;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.common.dto.chat.ReportChatEvidence;
import com.comatching.common.dto.chat.ReportChatEvidence.*;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReportChatEvidenceService {
    private static final int CONTEXT = 3;
    private static final int MAX_SELECTED = 50;
    private final ChatRoomRepository rooms;
    private final ChatMessageRepository messages;
    private final BlockService blocks;
    private final MongoTemplate mongo;

    public ReportChatEvidence snapshot(long reporter, long target, Range range) {
        var room = rooms.findById(range.roomId()).filter(r -> r.isVisibleTo(reporter) && r.otherMemberId(reporter) == target)
            .orElseThrow(() -> new BusinessException(GeneralErrorCode.NOT_FOUND));
        var cutoff = blocks.visibleAfter(reporter, target);
        var first = messages.findById(range.firstMessageId())
            .filter(m -> room.getId().equals(m.getRoomId()) && m.getType() != MessageType.READ
                && (cutoff == null || m.getCreatedAt().isAfter(cutoff)))
            .orElseThrow(() -> new BusinessException(GeneralErrorCode.BAD_REQUEST));

        var following = adjacent(first, cutoff, false, MAX_SELECTED + CONTEXT);
        if (following.isEmpty() || !following.get(0).getId().equals(first.getId())) {
            throw new BusinessException(GeneralErrorCode.BAD_REQUEST);
        }
        int selectedCount = 0;
        for (var message : following) {
            selectedCount++;
            if (message.getId().equals(range.lastMessageId())) break;
        }
        if (selectedCount > MAX_SELECTED || !following.get(selectedCount - 1).getId().equals(range.lastMessageId())) {
            throw new BusinessException(GeneralErrorCode.BAD_REQUEST, "같은 대화방에서 연속된 메시지를 최대 50개까지 선택해 주세요.");
        }

        var before = adjacent(first, cutoff, true, CONTEXT);
        Collections.reverse(before);
        var snapshot = new ArrayList<Message>();
        before.forEach(m -> snapshot.add(copy(m, Part.BEFORE)));
        following.subList(0, selectedCount).forEach(m -> snapshot.add(copy(m, Part.SELECTED)));
        int contextEnd = Math.min(following.size(), selectedCount + CONTEXT);
        following.subList(selectedCount, contextEnd).forEach(m -> snapshot.add(copy(m, Part.AFTER)));
        return new ReportChatEvidence(range, snapshot);
    }

    // Match the room history order even when several messages share a millisecond.
    private List<ChatMessage> adjacent(ChatMessage pivot, LocalDateTime cutoff, boolean before, int limit) {
        Criteria time = Criteria.where("createdAt");
        Criteria id = Criteria.where("id");
        Criteria sameTime = Criteria.where("createdAt").is(pivot.getCreatedAt())
            .andOperator(before ? id.lt(pivot.getId()) : id.gte(pivot.getId()));
        Criteria boundary = new Criteria().orOperator(before ? time.lt(pivot.getCreatedAt()) : time.gt(pivot.getCreatedAt()), sameTime);
        var visible = Criteria.where("roomId").is(pivot.getRoomId()).and("type").ne(MessageType.READ);
        if (cutoff != null) visible = visible.and("createdAt").gt(cutoff);
        var order = before ? Sort.Direction.DESC : Sort.Direction.ASC;
        var query = Query.query(new Criteria().andOperator(visible, boundary)).with(Sort.by(order, "createdAt", "id")).limit(limit);
        query.fields().exclude("pushOutbox");
        return mongo.find(query, ChatMessage.class);
    }

    private static Message copy(ChatMessage message, Part part) {
        return new Message(message.getId(), message.getSenderId(), message.getType().name(),
            message.getContent(), message.getCreatedAt().toString(), part);
    }
}
