package com.comatching.chat.domain.service.chat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.comatching.chat.domain.entity.*;
import com.comatching.chat.domain.enums.MessageType;
import com.comatching.chat.domain.repository.*;
import com.comatching.chat.domain.service.block.BlockServiceImpl;
import com.comatching.common.dto.chat.ReportChatEvidence.*;
import com.comatching.common.exception.BusinessException;
import static org.assertj.core.api.Assertions.*;

@DataMongoTest
@Tag("integration")
@Testcontainers
@ContextConfiguration(classes = ReportChatEvidenceIT.Config.class)
class ReportChatEvidenceIT {
    @Configuration
    @EnableMongoRepositories(basePackageClasses = ChatRoomRepository.class)
    @Import({ReportChatEvidenceService.class, BlockServiceImpl.class})
    static class Config {}
    @Container static GenericContainer<?> mongo = new GenericContainer<>("mongo:7").withExposedPorts(27017);
    @DynamicPropertySource static void mongoUri(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", () -> "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/report_test");
    }
    @Autowired ReportChatEvidenceService evidence;
    @Autowired ChatRoomRepository rooms;
    @Autowired ChatMessageRepository messages;
    @Autowired BlockServiceImpl blocks;

    @Test void capturesOnlyVisibleContiguousHistoryIncludingTiesAndRoomEdges() {
        var room = rooms.save(ChatRoom.builder().matchingId(11L).initiatorUserId(1L).targetUserId(2L).build());
        var otherRoom = rooms.save(ChatRoom.builder().matchingId(12L).initiatorUserId(1L).targetUserId(2L).build());
        LocalDateTime at = LocalDateTime.now().minusMinutes(1);
        List<String> ids = IntStream.range(0, 59).mapToObj(i -> messages.save(ChatMessage.builder().roomId(room.getId())
            .senderId(1L + i % 2).content("message " + i).createdAt(at).type(MessageType.TALK).build()).getId()).toList();
        var range = new Range(room.getId(), ids.get(5), ids.get(8));
        var result = evidence.snapshot(1L,2L,range);
        assertThat(result.messages()).extracting(Message::id).isEqualTo(ids.subList(2,12));
        assertThat(result.messages()).extracting(Message::part).containsExactly(Part.BEFORE,Part.BEFORE,Part.BEFORE,
            Part.SELECTED,Part.SELECTED,Part.SELECTED,Part.SELECTED,Part.AFTER,Part.AFTER,Part.AFTER);
        assertThat(evidence.snapshot(1L,2L,new Range(room.getId(),ids.get(0),ids.get(0))).messages()).hasSize(4);
        assertThat(evidence.snapshot(1L,2L,new Range(room.getId(),ids.get(9),ids.get(58))).messages()).hasSize(53);
        var foreign = messages.save(ChatMessage.builder().roomId(otherRoom.getId()).senderId(2L).type(MessageType.TALK).content("다른 방").createdAt(at).build());
        for (var invalid : List.of(new Range(room.getId(),ids.get(0),ids.get(50)),new Range(room.getId(),ids.get(4),ids.get(1)),
            new Range(room.getId(),ids.get(5),foreign.getId()),new Range(room.getId(),foreign.getId(),ids.get(5)))) {
            assertThatThrownBy(() -> evidence.snapshot(1,2,invalid)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> evidence.snapshot(3,2,range)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> evidence.snapshot(1,9,range)).isInstanceOf(BusinessException.class);
        blocks.blockUser(1L,2L);
        assertThatThrownBy(() -> evidence.snapshot(1,2,range)).isInstanceOf(BusinessException.class);
        blocks.unblockUser(1L,2L);
        var fresh = messages.save(ChatMessage.builder().roomId(room.getId()).senderId(2L).content("새 메시지")
            .createdAt(LocalDateTime.now().plusSeconds(1)).type(MessageType.TALK).build());
        assertThat(evidence.snapshot(1,2,new Range(room.getId(),fresh.getId(),fresh.getId())).messages())
            .extracting(Message::id).containsExactly(fresh.getId());
    }
}
