package com.comatching.item.domain.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.notice.dto.ActiveNoticeResponse;
import com.comatching.item.domain.notice.dto.NoticeCreateRequest;
import com.comatching.item.domain.notice.dto.NoticeUpdateRequest;
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

	@Test
	@DisplayName("관리자 공지사항 수정 시 제목, 내용, 노출 기간을 변경한다")
	void shouldUpdateNotice() {
		// given
		Notice notice = Notice.builder()
			.title("기존 제목")
			.content("기존 내용")
			.startTime(LocalDateTime.of(2026, 3, 10, 0, 0))
			.endTime(LocalDateTime.of(2026, 3, 11, 0, 0))
			.build();
		ReflectionTestUtils.setField(notice, "id", 7L);
		given(noticeRepository.findById(7L)).willReturn(Optional.of(notice));

		NoticeUpdateRequest request = new NoticeUpdateRequest(
			"수정 제목",
			"수정 내용",
			LocalDateTime.of(2026, 3, 12, 9, 0),
			LocalDateTime.of(2026, 3, 13, 9, 0)
		);

		// when
		noticeService.updateNotice(7L, request);

		// then
		assertThat(notice.getTitle()).isEqualTo("수정 제목");
		assertThat(notice.getContent()).isEqualTo("수정 내용");
		assertThat(notice.getStartTime()).isEqualTo(request.startTime());
		assertThat(notice.getEndTime()).isEqualTo(request.endTime());
	}

	@Test
	@DisplayName("존재하지 않는 공지사항 수정 시 예외가 발생한다")
	void shouldThrowWhenUpdatingMissingNotice() {
		// given
		NoticeUpdateRequest request = new NoticeUpdateRequest(
			"수정 제목",
			"수정 내용",
			LocalDateTime.of(2026, 3, 12, 9, 0),
			LocalDateTime.of(2026, 3, 13, 9, 0)
		);
		given(noticeRepository.findById(999L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> noticeService.updateNotice(999L, request))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(GeneralErrorCode.NOT_FOUND);
	}

	@Test
	@DisplayName("관리자 공지사항 삭제 시 공지사항을 삭제한다")
	void shouldDeleteNotice() {
		// given
		Notice notice = Notice.builder()
			.title("삭제 대상")
			.content("삭제 내용")
			.startTime(LocalDateTime.of(2026, 3, 10, 0, 0))
			.endTime(LocalDateTime.of(2026, 3, 11, 0, 0))
			.build();
		ReflectionTestUtils.setField(notice, "id", 5L);
		given(noticeRepository.findById(5L)).willReturn(Optional.of(notice));

		// when
		noticeService.deleteNotice(5L);

		// then
		then(noticeRepository).should().delete(notice);
	}

	@Test
	@DisplayName("존재하지 않는 공지사항 삭제 시 예외가 발생한다")
	void shouldThrowWhenDeletingMissingNotice() {
		// given
		given(noticeRepository.findById(888L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> noticeService.deleteNotice(888L))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(GeneralErrorCode.NOT_FOUND);
	}
}
