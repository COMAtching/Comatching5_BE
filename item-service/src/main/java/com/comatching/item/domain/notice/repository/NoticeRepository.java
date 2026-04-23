package com.comatching.item.domain.notice.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.comatching.item.domain.notice.entity.Notice;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

	List<Notice> findAllByStartTimeLessThanEqualAndEndTimeGreaterThanEqualOrderByStartTimeDescIdDesc(
		LocalDateTime currentTime,
		LocalDateTime currentTime2
	);
}
