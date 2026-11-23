package com.comatching.chat.domain.service.profile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.List;

import com.comatching.chat.domain.entity.ChatRoom;
import com.comatching.chat.domain.repository.ChatRoomRepository;
import com.comatching.chat.domain.service.block.BlockService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.chat.domain.dto.ChatMemberProfileResponse;
import com.comatching.chat.infra.client.MemberClient;
import com.comatching.chat.infra.client.MatchingHistoryClient;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.enums.SocialAccountType;
import com.comatching.common.dto.matching.MatchingHistoryReferenceResponse;
import com.comatching.common.dto.member.HobbyDto;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.member.ProfileTagDto;
import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

@ExtendWith(MockitoExtension.class)
class ChatProfileServiceImplTest {

	@Mock
	private ChatRoomRepository rooms;
	@Mock
	private BlockService blocks;
	@Mock
	private MemberClient memberClient;

	@Mock
	private MatchingHistoryClient matchingHistoryClient;

	@InjectMocks
	private ChatProfileServiceImpl chatProfileService;

	@Test
	@DisplayName("memberId로 채팅용 프로필을 조회한다")
	void getMemberProfile_returnsChatProfile() {
		// given
		Long currentMemberId = 1L;
		Long targetMemberId = 2L;
		Long historyId = 100L;
		given(rooms.findBetweenMembers(1L, 2L)).willReturn(List.of(room(1L, 2L)));
			given(memberClient.getProfiles(List.of(targetMemberId)))
				.willReturn(List.of(ProfileResponse.builder()
					.memberId(targetMemberId)
					.nickname("상대닉네임")
					.profileImageUrl("https://img.example/profile.png")
					.major("컴퓨터공학과")
					.birthDate(LocalDate.of(2000, 1, 1))
					.mbti("ENFP")
					.contactFrequency("자주")
					.hobbies(List.of(new HobbyDto(HobbyCategory.SPORTS, "축구")))
					.tags(List.of(new ProfileTagDto("밝은 분위기")))
					.song("좋은 노래")
					.intro("안녕하세요")
					.socialType(SocialAccountType.INSTAGRAM)
					.socialAccountId("comatching.official")
					.university("코매칭대학교")
					.email("hidden@example.com")
					.build()));
		given(matchingHistoryClient.getHistoryReference(currentMemberId, targetMemberId))
			.willReturn(new MatchingHistoryReferenceResponse(historyId, true));

		// when
		ChatMemberProfileResponse result = chatProfileService.getMemberProfile(currentMemberId, targetMemberId);

		// then
			assertThat(result.memberId()).isEqualTo(targetMemberId);
			assertThat(result.nickname()).isEqualTo("상대닉네임");
			assertThat(result.profileImageUrl()).isEqualTo("https://img.example/profile.png");
			assertThat(result.major()).isEqualTo("컴퓨터공학과");
			assertThat(result.age()).isPositive();
			assertThat(result.mbti()).isEqualTo("ENFP");
			assertThat(result.contactFrequency()).isEqualTo("자주");
			assertThat(result.hobbies()).containsExactly(new HobbyDto(HobbyCategory.SPORTS, "축구"));
			assertThat(result.tags()).containsExactly(new ProfileTagDto("밝은 분위기"));
			assertThat(result.song()).isEqualTo("좋은 노래");
			assertThat(result.intro()).isEqualTo("안녕하세요");
			assertThat(result.socialType()).isEqualTo(SocialAccountType.INSTAGRAM);
			assertThat(result.socialAccountId()).isEqualTo("comatching.official");
			assertThat(result.historyId()).isEqualTo(historyId);
			assertThat(result.favorite()).isTrue();
		}

	@Test
	@DisplayName("선택 프로필 값이 비어 있어도 채팅용 프로필을 반환한다")
	void getMemberProfile_allowsEmptyOptionalProfileFields() {
		// given
		Long currentMemberId = 1L;
		Long targetMemberId = 2L;
		ChatRoom active = room(2L, 1L);
		active.updateLastMessageInfo("first", java.time.LocalDateTime.now());
		given(rooms.findBetweenMembers(1L, 2L)).willReturn(List.of(active));
		given(matchingHistoryClient.getHistoryReference(1L, 2L)).willReturn(MatchingHistoryReferenceResponse.empty());
		given(memberClient.getProfiles(List.of(targetMemberId)))
			.willReturn(List.of(ProfileResponse.builder()
				.memberId(targetMemberId)
				.nickname("상대닉네임")
				.build()));

		// when
		ChatMemberProfileResponse result = chatProfileService.getMemberProfile(currentMemberId, targetMemberId);

		// then
		assertThat(result.memberId()).isEqualTo(targetMemberId);
		assertThat(result.age()).isNull();
		assertThat(result.hobbies()).isEmpty();
		assertThat(result.tags()).isEmpty();
		assertThat(result.song()).isNull();
		assertThat(result.intro()).isNull();
		assertThat(result.socialType()).isNull();
		assertThat(result.socialAccountId()).isNull();
		assertThat(result.historyId()).isNull();
		assertThat(result.favorite()).isFalse();
	}

	private ChatRoom room(long initiator, long target) {
		return ChatRoom.builder().initiatorUserId(initiator).targetUserId(target).build();
	}

	@Test
	void noRoomOrWaitingRecipientCannotReadProfile() {
		assertHidden();
		given(rooms.findBetweenMembers(1L, 2L)).willReturn(List.of(room(2L, 1L)));
		assertHidden();
	}

	@Test
	void onlyAnOutgoingActiveBlockHidesTheProfile() {
		ChatRoom active = room(2L, 1L);
		active.updateLastMessageInfo("first", java.time.LocalDateTime.now());
		given(rooms.findBetweenMembers(1L, 2L)).willReturn(List.of(active));
		given(blocks.isBlocked(1L, 2L)).willReturn(true, false);
		assertHidden();
		given(memberClient.getProfiles(List.of(2L))).willReturn(List.of(ProfileResponse.builder().memberId(2L).build()));
		assertThat(chatProfileService.getMemberProfile(1L, 2L).memberId()).isEqualTo(2L);
		then(blocks).should(never()).isBlocked(2L, 1L);
	}

	private void assertHidden() {
		assertThatThrownBy(() -> chatProfileService.getMemberProfile(1L, 2L))
			.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.NOT_FOUND));
		then(memberClient).shouldHaveNoInteractions();
		then(matchingHistoryClient).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("프로필이 없으면 404 예외를 던진다")
	void getMemberProfile_throwsNotFoundWhenProfileMissing() {
		// given
		Long currentMemberId = 1L;
		Long targetMemberId = 2L;
		given(rooms.findBetweenMembers(1L, 2L)).willReturn(List.of(room(1L, 2L)));
		given(memberClient.getProfiles(List.of(targetMemberId))).willReturn(List.of());

		// when & then
		assertThatThrownBy(() -> chatProfileService.getMemberProfile(currentMemberId, targetMemberId))
			.isInstanceOfSatisfying(BusinessException.class, e ->
				assertThat(e.getErrorCode()).isEqualTo(GeneralErrorCode.NOT_FOUND));
		then(matchingHistoryClient).shouldHaveNoInteractions();
	}
}
