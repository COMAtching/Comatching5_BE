package com.comatching.chat.domain.service.profile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.service.block.BlockService;
import com.comatching.chat.domain.service.block.BlockServiceImpl;
import com.comatching.chat.infra.client.MatchingHistoryClient;
import com.comatching.chat.infra.client.MemberClient;
import com.comatching.common.dto.matching.MatchingHistoryReferenceResponse;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

@Tag("integration")
@Testcontainers
@DataMongoTest
@ContextConfiguration(classes = ChatProfileVisibilityIT.Config.class)
class ChatProfileVisibilityIT {
	@Configuration
	@EnableMongoRepositories(basePackageClasses = ChatRoomRepository.class)
	@Import({ChatProfileServiceImpl.class, BlockServiceImpl.class})
	static class Config {}

	@Container
	static GenericContainer<?> mongo = new GenericContainer<>("mongo:7").withExposedPorts(27017);

	@DynamicPropertySource
	static void mongodb(DynamicPropertyRegistry properties) {
		properties.add("spring.data.mongodb.uri", () -> "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/profile_test");
		properties.add("spring.data.mongodb.database", () -> "profile_test");
	}

	@Autowired ChatRoomRepository rooms;
	@Autowired BlockService blocks;
	@Autowired ChatProfileService profiles;
	@MockitoBean MemberClient members;
	@MockitoBean MatchingHistoryClient history;

	@Test
	void visibleRoomAndViewersOutgoingBlockControlProfileAccess() {
		var room = rooms.save(ChatRoom.builder().matchingId(10L).initiatorUserId(1L).targetUserId(2L).build());
		given(members.getProfiles(List.of(1L))).willReturn(List.of(ProfileResponse.builder().memberId(1L).build()));
		given(members.getProfiles(List.of(2L))).willReturn(List.of(ProfileResponse.builder().memberId(2L).build()));
		given(history.getHistoryReference(2L, 1L)).willReturn(MatchingHistoryReferenceResponse.empty());

		assertHidden(3, 1);
		assertHidden(2, 1);
		then(members).shouldHaveNoInteractions();
		assertThat(profiles.getMemberProfile(1L, 2L).memberId()).isEqualTo(2L);

		room.updateLastMessageInfo("first", LocalDateTime.now());
		rooms.save(room);
		assertThat(profiles.getMemberProfile(2L, 1L).historyId()).isNull();
		blocks.blockUser(1L, 2L);
		assertHidden(1, 2);
		assertThat(profiles.getMemberProfile(2L, 1L).memberId()).isEqualTo(1L);
		blocks.unblockUser(1L, 2L);
		assertThat(profiles.getMemberProfile(1L, 2L).memberId()).isEqualTo(2L);
	}

	private void assertHidden(long viewer, long target) {
		assertThatThrownBy(() -> profiles.getMemberProfile(viewer, target)).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.getErrorCode()).isEqualTo(GeneralErrorCode.NOT_FOUND));
	}
}
