package com.comatching.common.exception.code;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

	String getCode();

	HttpStatus getHttpStatus();

	String getMessage();
}
