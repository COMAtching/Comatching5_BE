package com.comatching.common.config.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.dto.response.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * DLT 재적재 트리거.
 *
 * /api/internal/** 은 게이트웨이가 라우팅하지 않고(외부 접근 불가)
 * InternalApiAuthenticationFilter 가 X-Internal-Token 을 검사한다.
 * DLT 알림을 받은 운영자가 원인을 고친 뒤, 해당 서비스에 직접 호출한다:
 *
 *   curl -X POST -H "X-Internal-Token: $TOKEN" \
 *     "http://<서비스>/api/internal/kafka/dlt/redrive?topic=profile-updates"
 *
 * 자동 재적재를 두지 않는 이유: DLT 는 "재시도로 회복 안 된" 실패라 원인
 * 교정 없이 되돌리면 재시도-DLT-재적재 무한 순환이 된다. 사람이 고쳤다고
 * 판단한 시점에만 되돌리는 것이 맞다.
 */
@RestController
@ConditionalOnProperty(name = "comatching.kafka.dlt-topics")
@RequestMapping("/api/internal/kafka/dlt")
@RequiredArgsConstructor
public class KafkaDltRedriveController {

	private final KafkaDltRedriveService redriveService;

	@PostMapping("/redrive")
	public ApiResponse<KafkaDltRedriveService.RedriveResult> redrive(@RequestParam("topic") String topic) {
		return ApiResponse.ok(redriveService.redrive(topic));
	}
}
