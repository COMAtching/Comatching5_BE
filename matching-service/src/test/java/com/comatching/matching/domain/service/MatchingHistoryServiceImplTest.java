package com.comatching.matching.domain.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.common.dto.chat.ChatRoomReferenceResponse;
import com.comatching.common.dto.matching.MatchingHistoryReferenceResponse;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.member.ProfileTagDto;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.matching.domain.dto.MatchingHistoryResponse;
import com.comatching.matching.domain.entity.MatchingHistory;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.infra.client.ChatRoomClient;
import com.comatching.matching.infra.client.MemberClient;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("MatchingHistoryServiceImpl 테스트")
class MatchingHistoryServiceImplTest {

	@InjectMocks
	private MatchingHistoryServiceImpl matchingHistoryService;

	@Mock
	private MatchingHistoryRepository historyRepository;

	@Mock
	private MemberClient memberClient;

	@Mock
	private ChatRoomClient chatRoomClient;

	@Test
	@DisplayName("매칭 히스토리 응답에 파트너 장점 태그가 포함된다")
	void shouldReturnPartnerTagsInHistory() {
		// given
		Long memberId = 1L;
		Long partnerId = 2L;
		Pageable pageable = PageRequest.of(0, 10);
		MatchingHistory history = MatchingHistory.builder()
			.memberId(memberId)
			.partnerId(partnerId)
			.build();
		ProfileResponse partnerProfile = ProfileResponse.builder()
			.memberId(partnerId)
			.email("partner@example.com")
			.birthDate(LocalDate.of(2000, 1, 1))
			.tags(List.of(
				new ProfileTagDto("계란형 얼굴"),
				new ProfileTagDto("밝은 분위기")
			))
			.build();

		given(historyRepository.searchHistory(memberId, null, null, pageable))
			.willReturn(new PageImpl<>(List.of(history), pageable, 1));
		given(memberClient.getProfiles(List.of(partnerId))).willReturn(List.of(partnerProfile));

		// when
		PagingResponse<MatchingHistoryResponse> response = matchingHistoryService.getMyMatchingHistory(
			memberId, null, null, pageable, false
		);

		// then
		assertThat(response.content()).hasSize(1);
		assertThat(response.content().get(0).partner().tags()).extracting(ProfileTagDto::tag)
			.containsExactly("계란형 얼굴", "밝은 분위기");
		assertThat(response.content().get(0).partner().age()).isPositive();
	}

	@Test
	@DisplayName("매칭 히스토리 응답에 채팅방 ID가 포함된다")
	void shouldReturnChatRoomIdInHistory() {
		// given
		Long memberId = 1L;
		Long partnerId = 2L;
		Long historyId = 100L;
		Pageable pageable = PageRequest.of(0, 10);
		MatchingHistory history = MatchingHistory.builder()
			.memberId(memberId)
			.partnerId(partnerId)
			.build();
		ReflectionTestUtils.setField(history, "id", historyId);
		ProfileResponse partnerProfile = ProfileResponse.builder()
			.memberId(partnerId)
			.birthDate(LocalDate.of(2000, 1, 1))
			.build();

		given(historyRepository.searchHistory(memberId, null, null, pageable))
			.willReturn(new PageImpl<>(List.of(history), pageable, 1));
		given(memberClient.getProfiles(List.of(partnerId))).willReturn(List.of(partnerProfile));
		given(chatRoomClient.getChatRoomReferences(List.of(historyId)))
			.willReturn(List.of(new ChatRoomReferenceResponse(historyId, "room-100")));

		// when
		PagingResponse<MatchingHistoryResponse> response = matchingHistoryService.getMyMatchingHistory(
			memberId, null, null, pageable, false
		);

		// then
		assertThat(response.content()).hasSize(1);
		assertThat(response.content().get(0).historyId()).isEqualTo(historyId);
		assertThat(response.content().get(0).chatRoomId()).isEqualTo("room-100");
	}

	@Test
	@DisplayName("매칭 히스토리 파트너 응답에서 이메일과 생년월일을 노출하지 않는다")
	void shouldNotExposePartnerEmailOrBirthDateInHistory() throws Exception {
		// given
		Long memberId = 1L;
		Long partnerId = 2L;
		Pageable pageable = PageRequest.of(0, 10);
		MatchingHistory history = MatchingHistory.builder()
			.memberId(memberId)
			.partnerId(partnerId)
			.build();
		ProfileResponse partnerProfile = ProfileResponse.builder()
			.memberId(partnerId)
			.email("partner@example.com")
			.birthDate(LocalDate.of(2000, 1, 1))
			.build();

		given(historyRepository.searchHistory(memberId, null, null, pageable))
			.willReturn(new PageImpl<>(List.of(history), pageable, 1));
		given(memberClient.getProfiles(List.of(partnerId))).willReturn(List.of(partnerProfile));

		// when
		PagingResponse<MatchingHistoryResponse> response = matchingHistoryService.getMyMatchingHistory(
			memberId, null, null, pageable, false
		);
		String partnerJson = new ObjectMapper().writeValueAsString(response.content().get(0).partner());

		// then
		assertThat(partnerJson).contains("\"age\"");
		assertThat(partnerJson).doesNotContain("email", "birthDate", "partner@example.com", "2000-01-01");
	}

	@Test
	@DisplayName("현재 사용자와 상대방의 히스토리 참조를 조회한다")
	void shouldReturnHistoryReference() {
		// given
		Long memberId = 1L;
		Long partnerId = 2L;
		Long historyId = 100L;
		MatchingHistory history = MatchingHistory.builder()
			.memberId(memberId)
			.partnerId(partnerId)
			.build();
		ReflectionTestUtils.setField(history, "id", historyId);
		history.updateFavorite(true);

		given(historyRepository.findByMemberIdAndPartnerId(memberId, partnerId))
			.willReturn(Optional.of(history));

		// when
		MatchingHistoryReferenceResponse response = matchingHistoryService.getHistoryReference(memberId, partnerId);

		// then
		assertThat(response.historyId()).isEqualTo(historyId);
		assertThat(response.favorite()).isTrue();
	}

	@Test
	@DisplayName("히스토리 참조가 없으면 빈 응답을 반환한다")
	void shouldReturnEmptyHistoryReferenceWhenMissing() {
		// given
		Long memberId = 1L;
		Long partnerId = 2L;
		given(historyRepository.findByMemberIdAndPartnerId(memberId, partnerId))
			.willReturn(Optional.empty());

		// when
		MatchingHistoryReferenceResponse response = matchingHistoryService.getHistoryReference(memberId, partnerId);

		// then
		assertThat(response.historyId()).isNull();
		assertThat(response.favorite()).isFalse();
	}
}
