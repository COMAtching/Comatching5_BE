package com.comatching.matching.domain.component;

import java.util.List;

import com.comatching.common.dto.item.ItemConsumption;
import com.comatching.matching.domain.dto.MatchingRequest;

public interface MatchingItemPolicy {

	List<ItemConsumption> determine(MatchingRequest request);
}
