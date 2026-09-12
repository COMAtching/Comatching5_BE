package com.comatching.item.domain.admin.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;
import com.comatching.item.domain.admin.dto.AdminInventoryUpdateRequest;

@Component
public class AdminInventoryRequestValidator {

	private static final int MAX_REASON_LENGTH = 255;

	public void validate(AdminInventoryUpdateRequest request) {
		if (request == null || request.itemType() == null || request.action() == null || request.quantity() <= 0
			|| !StringUtils.hasText(request.reason()) || request.reason().length() > MAX_REASON_LENGTH) {
			throw new BusinessException(GeneralErrorCode.INVALID_INPUT_VALUE);
		}
	}
}
