package com.comatching.item.domain.grant.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.annotation.DistributedLock;
import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.grant.config.PreSignupMatchingTicketGrantProperties;
import com.comatching.item.domain.grant.entity.PreSignupItemGrantLedger;
import com.comatching.item.domain.grant.repository.PreSignupItemGrantLedgerRepository;
import com.comatching.item.domain.item.entity.Item;
import com.comatching.item.domain.item.enums.ItemHistoryType;
import com.comatching.item.domain.item.repository.ItemRepository;
import com.comatching.item.domain.item.service.ItemHistoryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PreSignupMatchingTicketGrantService {

	private static final LocalDateTime NO_EXPIRATION = LocalDateTime.of(2099, 12, 31, 23, 59, 59);
	private static final ItemType GRANT_ITEM_TYPE = ItemType.MATCHING_TICKET;

	private final PreSignupMatchingTicketGrantProperties properties;
	private final PreSignupItemGrantLedgerRepository ledgerRepository;
	private final ItemRepository itemRepository;
	private final ItemHistoryService historyService;

	@DistributedLock(key = "pre-signup:item-grant", identifier = "#memberId", leaseTime = 10L)
	public void grantMatchingTicket(Long memberId) {
		if (!properties.enabled()) {
			log.debug("pre-signup matching ticket grant disabled. memberId={}", memberId);
			return;
		}
		if (memberId == null || memberId <= 0) {
			log.warn("pre-signup matching ticket grant skipped by invalid memberId={}", memberId);
			return;
		}

		String campaignCode = properties.campaignCode().trim();
		if (ledgerRepository.existsByCampaignCodeAndMemberIdAndItemType(campaignCode, memberId, GRANT_ITEM_TYPE)) {
			log.info(
				"pre-signup matching ticket already granted. campaignCode={}, memberId={}",
				campaignCode,
				memberId
			);
			return;
		}

		ledgerRepository.save(PreSignupItemGrantLedger.builder()
			.campaignCode(campaignCode)
			.memberId(memberId)
			.itemType(GRANT_ITEM_TYPE)
			.build());

		itemRepository.save(Item.builder()
			.memberId(memberId)
			.itemType(GRANT_ITEM_TYPE)
			.quantity(properties.quantity())
			.expiredAt(NO_EXPIRATION)
			.build());

		historyService.saveHistory(
			memberId,
			GRANT_ITEM_TYPE,
			ItemHistoryType.EVENT,
			properties.quantity(),
			GRANT_ITEM_TYPE.getName()
		);

		log.info(
			"pre-signup matching ticket granted. campaignCode={}, memberId={}, quantity={}",
			campaignCode,
			memberId,
			properties.quantity()
		);
	}
}
