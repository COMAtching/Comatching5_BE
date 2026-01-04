package com.comatching.common.util;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class JsonUtil {

	private static final ObjectMapper objectMapper = new ObjectMapper()
		.registerModule(new JavaTimeModule());

	private JsonUtil() {
	}

	// 객체 -> JSON String
	public static String toJson(Object object) {
		try {
			return objectMapper.writeValueAsString(object);
		} catch (JsonProcessingException e) {
			throw new BusinessException(GeneralErrorCode.JSON_PARSE_ERROR);
		}
	}

	// JSON String -> 객체
	public static <T> T fromJson(String json, Class<T> clazz) {
		try {
			return objectMapper.readValue(json, clazz);
		} catch (JsonProcessingException e) {
			throw new BusinessException(GeneralErrorCode.JSON_PARSE_ERROR);
		}
	}
}
