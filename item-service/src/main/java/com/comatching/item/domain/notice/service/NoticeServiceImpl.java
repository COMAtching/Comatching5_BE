package com.comatching.item.domain.notice.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.notice.dto.ActiveNoticeResponse;
import com.comatching.item.domain.notice.dto.NoticeCreateRequest;
import com.comatching.item.domain.notice.dto.NoticeUpdateRequest;
import com.comatching.item.domain.notice.entity.Notice;
import com.comatching.item.domain.notice.repository.NoticeRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class NoticeServiceImpl implements NoticeService {

	private final NoticeRepository noticeRepository;

	@Override
	public void createNotice(NoticeCreateRequest request) {
		validatePeriod(request.startTime(), request.endTime());

		Notice notice = Notice.builder()
			.title(request.title())
			.content(request.content())
			.startTime(request.startTime())
			.endTime(request.endTime())
			.build();

		noticeRepository.save(notice);
	}

	@Override
	public void updateNotice(Long noticeId, NoticeUpdateRequest request) {
		validatePeriod(request.startTime(), request.endTime());

		Notice notice = findNoticeOrThrow(noticeId);
		notice.update(request.title(), request.content(), request.startTime(), request.endTime());
	}

	@Override
	public void deleteNotice(Long noticeId) {
		Notice notice = findNoticeOrThrow(noticeId);
		noticeRepository.delete(notice);
	}

	@Override
	@Transactional(readOnly = true)
	public List<ActiveNoticeResponse> getActiveNotices() {
		LocalDateTime currentTime = LocalDateTime.now();
		return noticeRepository
			.findAllByStartTimeLessThanEqualAndEndTimeGreaterThanEqualOrderByStartTimeDescIdDesc(currentTime, currentTime)
			.stream()
			.map(ActiveNoticeResponse::from)
			.toList();
	}

	private Notice findNoticeOrThrow(Long noticeId) {
		return noticeRepository.findById(noticeId)
			.orElseThrow(() -> new BusinessException(GeneralErrorCode.NOT_FOUND, "공지사항을 찾을 수 없습니다."));
	}

	private void validatePeriod(LocalDateTime startTime, LocalDateTime endTime) {
		if (startTime == null || endTime == null) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "시작시간과 종료시간은 필수입니다.");
		}
		if (!startTime.isBefore(endTime)) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE, "시작시간은 종료시간보다 이전이어야 합니다.");
		}
	}
}
