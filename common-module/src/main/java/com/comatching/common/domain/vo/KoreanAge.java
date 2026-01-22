package com.comatching.common.domain.vo;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KoreanAge {

	@Column(name = "age")
	private int value;

	private KoreanAge(int value) {
		this.value = value;
	}

	public static KoreanAge fromBirthDate(LocalDate birthDate) {
		if (birthDate == null) {
			return null;
		}
		int age = birthDate.until(LocalDate.now()).getYears() + 1;
		return new KoreanAge(age);
	}

	public static KoreanAge of(int age) {
		return new KoreanAge(age);
	}

	public boolean isEqual(KoreanAge other) {
		return other != null && this.value == other.value;
	}

	public boolean isOlderThan(KoreanAge other) {
		return other != null && this.value > other.value;
	}

	public boolean isYoungerThan(KoreanAge other) {
		return other != null && this.value < other.value;
	}
}
