package com.comatching.matching.domain.component;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.common.dto.item.ItemConsumption;
import com.comatching.matching.domain.dto.MatchingRequest;

@Component
public class MatchingItemPolicyImpl implements MatchingItemPolicy {

	@Override
	public List<ItemConsumption> determine(MatchingRequest request) {

		List<ItemConsumption> consumptions = new ArrayList<>();

		consumptions.add(new ItemConsumption(ItemType.MATCHING_TICKET, 1));

		int cnt = 0;
		if (request.sameMajorOption())
			cnt++;
		if (request.importantOption() != null)
			cnt++;

		if (cnt != 0) {
			consumptions.add(new ItemConsumption(ItemType.OPTION_TICKET, cnt));
		}

		return consumptions;
	}
}
