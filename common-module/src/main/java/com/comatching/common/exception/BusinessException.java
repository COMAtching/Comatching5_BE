package com.comatching.common.exception;

import com.comatching.common.exception.code.ErrorCode;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;
	private final Object errorData;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
		this.errorData = null;
	}

	public BusinessException(ErrorCode errorCode, String customMessage) {
		super(customMessage);
		this.errorCode = errorCode;
		this.errorData = null;
	}

	public BusinessException(ErrorCode errorCode, Object errorData, String customMessage) {
		super(customMessage);
		this.errorCode = errorCode;
		this.errorData = errorData;
	}
}
