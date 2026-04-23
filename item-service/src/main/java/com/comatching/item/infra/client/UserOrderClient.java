package com.comatching.item.infra.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import com.comatching.common.dto.member.OrdererInfoDto;
import com.comatching.common.dto.member.RealNameUpdateRequestDto;

@FeignClient(name = "user-service-order", url = "${user-service.url}", path = "/api/internal/users")
public interface UserOrderClient {

	@GetMapping("/{memberId}/orderer-info")
	OrdererInfoDto getOrdererInfo(@PathVariable("memberId") Long memberId);

	@PatchMapping("/{memberId}/real-name")
	void updateRealName(
		@PathVariable("memberId") Long memberId,
		@RequestBody RealNameUpdateRequestDto request
	);
}
