package com.comatching.common.dto.member;

public record AdminGiftCardUserProfileDto(
    Long id,
    String email,
    String realName,
    String nickname
) {
}
