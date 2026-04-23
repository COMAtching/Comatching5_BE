package com.comatching.item.domain.notice.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
