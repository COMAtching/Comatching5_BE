package com.comatching.matching.domain.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Mbti {

	private static final int SCORE_PER_TRAIT = 10;

	@Column(name = "mbti", length = 4)
	private String value;

	public Mbti(String value) {
		if (value != null && !value.isBlank()) {
			this.value = value.toUpperCase();
		}
	}

	/**
	 * 요청 MBTI의 모든 문자가 후보자 MBTI에 포함되는지 확인
	 * 예: "IS"가 "ISTJ"에 모두 포함되면 true
	 */
	public boolean containsAll(Mbti request) {
		if (request == null || request.value == null || this.value == null) {
			return true;
		}
		for (char c : request.value.toCharArray()) {
			if (this.value.indexOf(c) == -1) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 일치하는 MBTI 특성 개수에 따른 점수 계산
	 */
	public int calculateScore(Mbti request) {
		if (request == null || request.value == null || this.value == null) {
			return 0;
		}
		int matchCount = 0;
		for (char c : request.value.toCharArray()) {
			if (this.value.indexOf(c) >= 0) {
				matchCount++;
			}
		}
		return matchCount * SCORE_PER_TRAIT;
	}
}
