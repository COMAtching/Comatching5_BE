package com.comatching.user.domain.admin.notice.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notice")
public class Notice {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String title;

	@Lob
	@Column(nullable = false, columnDefinition = "TEXT")
	private String content;

	@Column(nullable = false)
	private LocalDateTime startTime;

	@Column(nullable = false)
	private LocalDateTime endTime;

	@Builder
	public Notice(String title, String content, LocalDateTime startTime, LocalDateTime endTime) {
		this.title = title;
		this.content = content;
		this.startTime = startTime;
		this.endTime = endTime;
	}

	public void update(String title, String content, LocalDateTime startTime, LocalDateTime endTime) {
		this.title = title;
		this.content = content;
		this.startTime = startTime;
		this.endTime = endTime;
	}
}
