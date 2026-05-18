package com.comatching.item.domain.grant.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.grant.entity.PreSignupItemGrantLedger;

public interface PreSignupItemGrantLedgerRepository extends JpaRepository<PreSignupItemGrantLedger, Long> {

	boolean existsByCampaignCodeAndMemberIdAndItemType(String campaignCode, Long memberId, ItemType itemType);
}
