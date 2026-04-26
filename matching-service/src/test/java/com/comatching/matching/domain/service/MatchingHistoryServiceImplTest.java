package com.comatching.matching.domain.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.dto.member.ProfileTagDto;
import com.comatching.common.dto.response.PagingResponse;
import com.comatching.matching.domain.dto.MatchingHistoryResponse;
import com.comatching.matching.domain.entity.MatchingHistory;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.infra.client.MemberClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("MatchingHistoryServiceImpl 테스트")
class MatchingHistoryServiceImplTest {

	@InjectMocks
	private MatchingHistoryServiceImpl matchingHistoryService;

	@Mock
	private MatchingHistoryRepository historyRepository;

	@Mock
	private MemberClient memberClient;

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
	}
}
