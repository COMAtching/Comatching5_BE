package com.comatching.chat.domain.repository;

import java.util.List;
import java.util.Map;

public interface ChatMessageRepositoryCustom {

	Map<String, Long> countUnreadMessagesByRoom(List<UnreadCountCondition> conditions, Long myMemberId);
}
