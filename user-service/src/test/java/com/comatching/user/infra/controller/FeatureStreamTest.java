package com.comatching.user.infra.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.comatching.common.operations.Feature;
import com.comatching.common.operations.FeatureState;
import com.comatching.user.domain.operations.FeatureOperations;

class FeatureStreamTest {
	@Test
	void idleSubscribersShareTheScheduledReadAndNeverAcquireARequestScopedDbConnection() throws Exception {
		FeatureOperations operations = mock(FeatureOperations.class);
		FeatureController controller = new FeatureController(operations);
		for (int tab = 0; tab < 12; tab++) {
			assertThat(controller.stream().getBody()).isNotNull();
		}
		verifyNoInteractions(operations);
		given(operations.snapshot()).willReturn(List.of(new FeatureState(Feature.CHAT, false, "", 3)));
		controller.publishChanges();
		verify(operations).snapshot();
	}
}
