package com.comatching.user.domain.admin.notice.repository;


import com.comatching.user.domain.admin.notice.entity.Notice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

	List<Notice> findAllByOrderByStartTimeDescIdDesc();

	List<Notice> findAllByStartTimeLessThanEqualAndEndTimeGreaterThanEqualOrderByStartTimeDescIdDesc(
		LocalDateTime currentTime,
		LocalDateTime currentTime2
	);
}
