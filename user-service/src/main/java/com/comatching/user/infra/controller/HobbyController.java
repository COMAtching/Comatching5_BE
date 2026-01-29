package com.comatching.user.infra.controller;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.comatching.common.domain.enums.DefaultHobby;
import com.comatching.common.domain.enums.HobbyCategory;
import com.comatching.common.dto.member.HobbyCategoryResponse;
import com.comatching.common.dto.response.ApiResponse;

@RestController
@RequestMapping("/api/hobbies")
public class HobbyController {

	@GetMapping("/categories")
	public ResponseEntity<ApiResponse<List<HobbyCategoryResponse>>> getCategories() {
		List<HobbyCategoryResponse> response = Arrays.stream(HobbyCategory.values())
			.map(category -> {
				List<HobbyCategoryResponse.HobbyItem> hobbies = DefaultHobby.getByCategory(category)
					.stream()
					.map(hobby -> new HobbyCategoryResponse.HobbyItem(
						hobby.name(),
						hobby.getDisplayName()
					))
					.toList();

				return new HobbyCategoryResponse(
					category.name(),
					category.getDescription(),
					hobbies
				);
			})
			.toList();

		return ResponseEntity.ok(ApiResponse.ok(response));
	}
}
