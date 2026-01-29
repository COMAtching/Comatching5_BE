package com.comatching.matching.domain.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.comatching.common.domain.enums.ContactFrequency;
import com.comatching.common.domain.enums.Gender;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.item.ItemConsumption;
import com.comatching.common.dto.member.ProfileResponse;
import com.comatching.common.exception.BusinessException;
import com.comatching.matching.domain.component.MatchingItemPolicy;
import com.comatching.matching.domain.component.MatchingProcessor;
import com.comatching.matching.domain.dto.MatchingRequest;
import com.comatching.matching.domain.dto.MatchingResponse;
import com.comatching.matching.domain.entity.MatchingCandidate;
import com.comatching.matching.domain.entity.MatchingHistory;
import com.comatching.matching.domain.repository.history.MatchingHistoryRepository;
import com.comatching.matching.global.exception.MatchingErrorCode;
import com.comatching.matching.infra.client.ItemClient;
import com.comatching.matching.infra.client.MemberClient;
import com.comatching.matching.infra.kafka.MatchingEventProducer;

@ExtendWith(MockitoExtension.class)
class MatchingServiceImplTest {

	@InjectMocks
	private MatchingServiceImpl matchingService;

	@Mock
	private MatchingHistoryRepository historyRepository;

	@Mock
	private MemberClient memberClient;

	@Mock
	private ItemClient itemClient;

	@Mock
	private MatchingEventProducer matchingEventProducer;

	@Mock
	private MatchingItemPolicy matchingItemPolicy;

	@Mock
	private MatchingProcessor matchingProcessor;

	private ProfileResponse createProfile(Long memberId, Gender gender) {
		return ProfileResponse.builder()
			.memberId(memberId)
			.gender(gender)
			.mbti("ISTJ")
			.major("컴퓨터공학과")
			.birthDate(LocalDate.of(2000, 1, 1))
			.build();
	}

	private MatchingCandidate createCandidate(Long memberId) {
		return MatchingCandidate.create(
			memberId, 1L, Gender.FEMALE, "ENFP", "디자인학과",
			ContactFrequency.FREQUENT, List.of(HobbyCategory.SPORTS),
			LocalDate.of(2000, 1, 1), true
		);
	}

	@Nested
	@DisplayName("match 메서드")
	class Match {

		@Test
		@DisplayName("매칭에 성공하면 MatchingResponse를 반환한다")
		void shouldReturnMatchingResponseOnSuccess() {
			// given
			Long memberId = 1L;
			Long partnerId = 2L;
			MatchingRequest request = new MatchingRequest(null, "IS", null, null, false, null);

			ProfileResponse myProfile = createProfile(memberId, Gender.MALE);
			ProfileResponse partnerProfile = createProfile(partnerId, Gender.FEMALE);
			MatchingCandidate partner = createCandidate(partnerId);

			List<ItemConsumption> consumptions = List.of(new ItemConsumption(ItemType.MATCHING_TICKET, 1));
			MatchingHistory savedHistory = MatchingHistory.builder()
				.memberId(memberId)
				.partnerId(partnerId)
				.build();

			given(memberClient.getProfile(anyLong())).willAnswer(invocation -> {
				Long id = invocation.getArgument(0);
				if (id.equals(memberId)) {
					return myProfile;
				}
				return partnerProfile;
			});
			given(matchingItemPolicy.determine(request)).willReturn(consumptions);
			given(matchingProcessor.process(memberId, myProfile, request)).willReturn(partner);
			given(historyRepository.save(any(MatchingHistory.class))).willReturn(savedHistory);

			// when
			MatchingResponse response = matchingService.match(memberId, request);

			// then
			assertThat(response).isNotNull();
			assertThat(response.memberId()).isEqualTo(partnerId);
			verify(itemClient).useItem(memberId, ItemType.MATCHING_TICKET, 1);
			verify(matchingEventProducer).sendMatchingSuccess(any());
		}

		@Test
		@DisplayName("아이템 사용 실패시 BusinessException을 던지고 이미 소비된 아이템을 환불한다")
		void shouldRefundAndThrowExceptionWhenItemUseFails() {
			// given
			Long memberId = 1L;
			MatchingRequest request = new MatchingRequest(null, null, null, null, false, null);

			ProfileResponse myProfile = createProfile(memberId, Gender.MALE);
			List<ItemConsumption> consumptions = List.of(
				new ItemConsumption(ItemType.MATCHING_TICKET, 1),
				new ItemConsumption(ItemType.OPTION_TICKET, 1)
			);

			given(memberClient.getProfile(memberId)).willReturn(myProfile);
			given(matchingItemPolicy.determine(request)).willReturn(consumptions);
			willDoNothing().given(itemClient).useItem(memberId, ItemType.MATCHING_TICKET, 1);
			willThrow(new RuntimeException("아이템 부족"))
				.given(itemClient).useItem(memberId, ItemType.OPTION_TICKET, 1);

			// when & then
			assertThatThrownBy(() -> matchingService.match(memberId, request))
				.isInstanceOf(BusinessException.class);

			verify(itemClient).addItem(eq(memberId), any());
		}

		@Test
		@DisplayName("매칭 처리 실패시 아이템을 환불한다")
		void shouldRefundItemsWhenMatchingFails() {
			// given
			Long memberId = 1L;
			MatchingRequest request = new MatchingRequest(null, null, null, null, false, null);

			ProfileResponse myProfile = createProfile(memberId, Gender.MALE);
			List<ItemConsumption> consumptions = List.of(new ItemConsumption(ItemType.MATCHING_TICKET, 1));

			given(memberClient.getProfile(memberId)).willReturn(myProfile);
			given(matchingItemPolicy.determine(request)).willReturn(consumptions);
			given(matchingProcessor.process(memberId, myProfile, request))
				.willThrow(new BusinessException(MatchingErrorCode.NO_MATCHING_CANDIDATE));

			// when & then
			assertThatThrownBy(() -> matchingService.match(memberId, request))
				.isInstanceOf(BusinessException.class);

			verify(itemClient).addItem(eq(memberId), any());
		}

		@Test
		@DisplayName("매칭 성공시 히스토리를 저장하고 이벤트를 발행한다")
		void shouldSaveHistoryAndPublishEventOnSuccess() {
			// given
			Long memberId = 1L;
			Long partnerId = 2L;
			MatchingRequest request = new MatchingRequest(null, null, null, null, false, null);

			ProfileResponse myProfile = createProfile(memberId, Gender.MALE);
			ProfileResponse partnerProfile = createProfile(partnerId, Gender.FEMALE);
			MatchingCandidate partner = createCandidate(partnerId);
			MatchingHistory savedHistory = MatchingHistory.builder()
				.memberId(memberId)
				.partnerId(partnerId)
				.build();

			given(memberClient.getProfile(anyLong())).willAnswer(invocation -> {
				Long id = invocation.getArgument(0);
				if (id.equals(memberId)) {
					return myProfile;
				}
				return partnerProfile;
			});
			given(matchingItemPolicy.determine(request)).willReturn(List.of());
			given(matchingProcessor.process(memberId, myProfile, request)).willReturn(partner);
			given(historyRepository.save(any(MatchingHistory.class))).willReturn(savedHistory);

			// when
			matchingService.match(memberId, request);

			// then
			verify(historyRepository).save(any(MatchingHistory.class));
			verify(matchingEventProducer).sendMatchingSuccess(any());
		}
	}
}
