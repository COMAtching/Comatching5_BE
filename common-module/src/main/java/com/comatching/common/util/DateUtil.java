package com.comatching.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DateUtil {

	private static final String DEFAULT_PATTERN = "yyyy-MM-dd HH:mm:ss";
	private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DEFAULT_PATTERN);

	private DateUtil() {
	}

	// LocalDateTime -> String
	public static String toString(LocalDateTime date) {
		if (date == null)
			return "";
		return date.format(formatter);
	}

	// String -> LocalDateTime
	public static LocalDateTime toLocalDateTime(String dateStr) {
		return LocalDateTime.parse(dateStr, formatter);
	}

	// 현재 시간을 문자열로
	public static String nowString() {
		return LocalDateTime.now().format(formatter);
	}
}
