package com.comatching.common.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

public record PagingResponse<T>(
	List<T> content,
	int currentPage,
	int size,
	long totalElements,
	int totalPages,
	boolean hasNext,
	boolean hasPrevious
) {
	public static <T> PagingResponse<T> from(Page<T> page) {
		return new PagingResponse<>(
			page.getContent(),
			page.getNumber(),
			page.getSize(),
			page.getTotalElements(),
			page.getTotalPages(),
			page.hasNext(),
			page.hasPrevious()
		);
	}
}