package com.comatching.common.operations;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.comatching.common.exception.BusinessException;
import com.comatching.common.exception.code.GeneralErrorCode;

/** Admission decisions read the owner's committed state. Browser events are only a notification. */
public class RemoteFeatureGate implements FeatureGate {
	private final RestClient client;

	public RemoteFeatureGate(@Value("${user-service.url:http://localhost:9000}") String url) {
		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(1));
		factory.setReadTimeout(Duration.ofSeconds(2));
		this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
	}

	@Override
	public FeatureState state(Feature feature) {
		try {
			FeatureState value = client.get().uri("/api/public/operations/{feature}", feature)
				.retrieve().body(FeatureState.class);
			if (value != null && value.feature() == feature) return value;
		} catch (RestClientException unavailable) {
			throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
		}
		throw new BusinessException(GeneralErrorCode.INTERNAL_SERVICE_UNAVAILABLE);
	}
}
