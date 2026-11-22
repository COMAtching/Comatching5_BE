package com.comatching.common.config.kafka;

import java.util.Arrays;
import com.comatching.common.dto.event.order.ItemsGranted;
import com.comatching.common.dto.event.order.RefundCompleted;

/**
 * 원본 토픽과 실제 소비 그룹의 조합마다 DLT·replay 목적지를 고정한다.
 */
public enum KafkaEventRoute {
    CANDIDATE_WITHDRAW("member-withdraw", "matching-candidate-sync-group", ReplayTopics.CANDIDATE_WITHDRAW),
    CANDIDATE_PROFILE("profile-updates", "matching-candidate-sync-group", ReplayTopics.CANDIDATE_PROFILE),
    WITHDRAW_MAIL("member-withdraw", "notification-withdraw-group", ReplayTopics.WITHDRAW_MAIL),
    SIGNUP_MAIL("member-signup", "notification-signup-group", ReplayTopics.SIGNUP_MAIL),
    CHAT_NOTIFICATION("chat-message-sent", "notification-group", ReplayTopics.CHAT_NOTIFICATION),
    CHAT_ROOM_CREATE("matching-success-topic", "chat-service-group", ReplayTopics.CHAT_ROOM_CREATE),
    PRE_SIGNUP_ITEM("member-signup", "item-pre-signup-matching-ticket-grant-group", ReplayTopics.PRE_SIGNUP_ITEM),
    ITEMS_GRANTED_MAIL(ItemsGranted.TOPIC, ItemsGrantedGroups.MAIL, ReplayTopics.ITEMS_GRANTED_MAIL),
    ITEMS_GRANTED_PUSH(ItemsGranted.TOPIC, ItemsGrantedGroups.PUSH, ReplayTopics.ITEMS_GRANTED_PUSH),
    REFUND_MAIL(RefundCompleted.TOPIC, Groups.REFUND_MAIL, ReplayTopics.REFUND_MAIL);

    private final String sourceTopic;
    private final String groupId;
    private final String replayTopic;

    KafkaEventRoute(String sourceTopic, String groupId, String replayTopic) {
        this.sourceTopic = sourceTopic;
        this.groupId = groupId;
        this.replayTopic = replayTopic;
    }

    public String sourceTopic() {
        return sourceTopic;
    }

    public String groupId() {
        return groupId;
    }

    public String replayTopic() {
        return replayTopic;
    }

    public String dltTopic() {
        return sourceTopic + "." + groupId + ".DLT";
    }

    public boolean acceptsTopic(String topic) {
        return sourceTopic.equals(topic) || replayTopic.equals(topic);
    }

    public static KafkaEventRoute forRecord(String topic, String actualGroupId) {
        return Arrays.stream(values())
                .filter(route -> route.groupId.equals(actualGroupId) && route.acceptsTopic(topic))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "등록되지 않은 Kafka 소비 경로: topic=" + topic + ", group=" + actualGroupId));
    }

    // 애너테이션에는 메서드 호출을 넣을 수 없어 컴파일 타임 상수로 제공한다.
    public static final class ItemsGrantedGroups {
        private ItemsGrantedGroups() {}
        public static final String MAIL = "notification-items-granted-mail-group";
        public static final String PUSH = "notification-items-granted-push-group";
    }

    public static final class Groups {
        private Groups() {}
        public static final String REFUND_MAIL = "notification-refund-mail-group";
    }

    public static final class ReplayTopics {
        private ReplayTopics() {
        }

        public static final String CANDIDATE_WITHDRAW = "member-withdraw.matching-candidate-sync-group.replay";
        public static final String CANDIDATE_PROFILE = "profile-updates.matching-candidate-sync-group.replay";
        public static final String WITHDRAW_MAIL = "member-withdraw.notification-withdraw-group.replay";
        public static final String SIGNUP_MAIL = "member-signup.notification-signup-group.replay";
        public static final String CHAT_NOTIFICATION = "chat-message-sent.notification-group.replay";
        public static final String ITEMS_GRANTED_MAIL = ItemsGranted.TOPIC + "." + ItemsGrantedGroups.MAIL + ".replay";
        public static final String REFUND_MAIL = RefundCompleted.TOPIC + "." + Groups.REFUND_MAIL + ".replay";
        public static final String ITEMS_GRANTED_PUSH = ItemsGranted.TOPIC + "." + ItemsGrantedGroups.PUSH + ".replay";
        public static final String CHAT_ROOM_CREATE = "matching-success-topic.chat-service-group.replay";
        public static final String PRE_SIGNUP_ITEM = "member-signup.item-pre-signup-matching-ticket-grant-group.replay";
    }
}
