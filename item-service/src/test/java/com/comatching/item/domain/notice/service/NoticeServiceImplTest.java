package com.comatching.item.domain.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.common.exception.BusinessException;
import com.comatching.item.domain.notice.dto.ActiveNoticeResponse;
import com.comatching.item.domain.notice.dto.NoticeCreateRequest;
import com.comatching.item.domain.notice.entity.Notice;
import com.comatching.item.domain.notice.repository.NoticeRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("NoticeServiceImpl 테스트")
class NoticeServiceImplTest {

	@InjectMocks
	private NoticeServiceImpl noticeService;

	@Mock
	private NoticeRepository noticeRepository;

	@Test
	@DisplayName("관리자 공지사항 등록 시 내용을 그대로 저장한다")
	void shouldSaveNoticeWithOriginalContent() {
		// given
		String content = "첫째 줄\n둘째 줄\n셋째 줄";
		NoticeCreateRequest request = new NoticeCreateRequest(
			"점검 안내",
			content,
			LocalDateTime.of(2026, 3, 12, 14, 0),
			LocalDateTime.of(2026, 3, 13, 2, 0)
		);
		given(noticeRepository.save(any(Notice.class))).willAnswer(invocation -> invocation.getArgument(0));

		// when
		noticeService.createNotice(request);

		// then
		ArgumentCaptor<Notice> noticeCaptor = ArgumentCaptor.forClass(Notice.class);
		then(noticeRepository).should().save(noticeCaptor.capture());
		Notice savedNotice = noticeCaptor.getValue();
		assertThat(savedNotice.getTitle()).isEqualTo("점검 안내");
		assertThat(savedNotice.getContent()).isEqualTo(content);
		assertThat(savedNotice.getStartTime()).isEqualTo(request.startTime());
		assertThat(savedNotice.getEndTime()).isEqualTo(request.endTime());
	}

	@Test
	@DisplayName("시작시간이 종료시간보다 같거나 늦으면 등록에 실패한다")
	void shouldThrowWhenStartTimeIsNotBeforeEndTime() {
		// given
		LocalDateTime startTime = LocalDateTime.of(2026, 3, 12, 10, 0);
		NoticeCreateRequest request = new NoticeCreateRequest(
			"잘못된 공지",
			"내용",
			startTime,
			startTime
		);

		// when & then
		assertThatThrownBy(() -> noticeService.createNotice(request))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("시작시간 또는 종료시간이 null이면 등록에 실패한다")
	void shouldThrowWhenPeriodIsNull() {
		// given
		NoticeCreateRequest request = new NoticeCreateRequest(
			"공지",
			"내용",
			null,
			LocalDateTime.of(2026, 3, 12, 11, 0)
		);

		// when & then
		assertThatThrownBy(() -> noticeService.createNotice(request))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("현재 시각에 활성화된 공지사항 id, 제목, 내용을 그대로 반환한다")
	void shouldReturnActiveNotices() {
		// given
		Notice notice = Notice.builder()
			.title("공지 제목")
			.content("한 줄\n두 줄")
			.startTime(LocalDateTime.of(2026, 3, 12, 0, 0))
			.endTime(LocalDateTime.of(2026, 3, 20, 23, 59))
			.build();
		ReflectionTestUtils.setField(notice, "id", 10L);

		given(noticeRepository.findAllByStartTimeLessThanEqualAndEndTimeGreaterThanEqualOrderByStartTimeDescIdDesc(
			any(LocalDateTime.class), any(LocalDateTime.class)))
			.willReturn(List.of(notice));

		// when
		List<ActiveNoticeResponse> responses = noticeService.getActiveNotices();

		// then
		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).noticeId()).isEqualTo(10L);
		assertThat(responses.get(0).title()).isEqualTo("공지 제목");
		assertThat(responses.get(0).content()).isEqualTo("한 줄\n두 줄");
	}
}
