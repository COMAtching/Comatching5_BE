package com.comatching.item.domain.admin.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.admin.dto.AdminInventoryCounts;
import com.comatching.item.domain.item.repository.ItemRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminItemQueryService {
	private final ItemRepository itemRepository;

	public Map<Long, AdminInventoryCounts> getInventoryCounts(List<Long> memberIds) {
		if (memberIds == null || memberIds.isEmpty()) {
			return Map.of();
		}

		Map<Long, long[]> countsByMemberId = new HashMap<>();
		for (ItemRepository.MemberItemQuantity quantity : itemRepository.sumUsableQuantityByMemberIds(memberIds)) {
			long[] counts = countsByMemberId.computeIfAbsent(quantity.getMemberId(), ignored -> new long[2]);
			if (quantity.getItemType() == ItemType.MATCHING_TICKET) {
				counts[0] = quantity.getQuantity();
			}
			if (quantity.getItemType() == ItemType.OPTION_TICKET) {
				counts[1] = quantity.getQuantity();
			}
		}

		Map<Long, AdminInventoryCounts> result = new HashMap<>();
		countsByMemberId.forEach((memberId, counts) ->
			result.put(memberId, new AdminInventoryCounts(counts[0], counts[1]))
		);
		return result;
	}
}
