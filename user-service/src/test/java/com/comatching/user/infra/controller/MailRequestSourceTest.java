package com.comatching.user.infra.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import com.comatching.user.domain.mail.service.VerificationMailLimitException;

class MailRequestSourceTest {
	private final MailRequestSource source = new MailRequestSource("192.0.2.0/24");

	@Test void onlyTrustedTransportPeersCanSupplyForwardingHops() {
		var request = new MockHttpServletRequest();
		request.setRemoteAddr("198.51.100.1");
		request.addHeader("X-Forwarded-For", "203.0.113.11, 198.51.100.2, 192.0.2.4");
		assertThat(source.of(request)).isEqualTo("198.51.100.1");
		request.setRemoteAddr("192.0.2.1");
		assertThat(source.of(request)).isEqualTo("198.51.100.2");
		assertThat(new MailRequestSource("").of(request)).isEqualTo("192.0.2.1");
	}

	@Test void malformedForwardingFallsBackToTheTransportPeer() {
		var request = new MockHttpServletRequest();
		request.setRemoteAddr("192.0.2.1");
		request.addHeader("X-Forwarded-For", "untrusted-label.invalid");
		assertThat(source.of(request)).isEqualTo("192.0.2.1");
	}

	@Test void throttleReturnsRetryAfterInHeaderAndBody() {
		var response = new VerificationMailAdvice().limited(new VerificationMailLimitException(37));
		assertThat(response.getStatusCode().value()).isEqualTo(429);
		assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("37");
		assertThat(response.getBody().getData()).containsEntry("retryAfterSeconds",37L);
	}
}
