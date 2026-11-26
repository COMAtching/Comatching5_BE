package com.comatching.common.operations;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

public interface FeatureGate {
	FeatureState state(Feature feature);

	default void requireOpen(Feature feature) {
		FeatureState current = state(feature);
		if (!current.open()) {
			throw new BusinessException(GeneralErrorCode.FEATURE_CLOSED, current, current.message());
		}
	}
}
