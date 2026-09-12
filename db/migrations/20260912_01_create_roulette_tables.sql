-- item-service roulette schema migration
-- Run against the production MySQL instance before deploying item-service.

CREATE TABLE IF NOT EXISTS comatching_item.roulette_reward (
    id BIGINT NOT NULL AUTO_INCREMENT,
    roulette_type ENUM('FREE', 'SPECIAL') NOT NULL,
    reward_name VARCHAR(255) NOT NULL,
    reward_type ENUM(
        'MATCHING_TICKET',
        'OPTION_TICKET',
        'FULL_SET',
        'GIFT_CARD',
        'NONE'
    ) NOT NULL,
    quantity INT NOT NULL,
    range_start INT NOT NULL,
    range_end INT NOT NULL,
    remaining_count INT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comatching_item.roulette_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    reward_id BIGINT NOT NULL,
    roulette_type ENUM('FREE', 'SPECIAL') NOT NULL,
    participated_at DATETIME(6) NOT NULL,
    participation_date DATE NOT NULL,
    reward_granted BIT(1) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_roulette_history_member_type_date
        UNIQUE (member_id, roulette_type, participation_date),
    CONSTRAINT fk_roulette_history_reward
        FOREIGN KEY (reward_id)
        REFERENCES comatching_item.roulette_reward (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO comatching_item.roulette_reward
(
    roulette_type,
    reward_name,
    reward_type,
    quantity,
    range_start,
    range_end,
    remaining_count
)
VALUES
    -- 45%
    ('FREE', '옵션권 1장', 'OPTION_TICKET', 1, 1, 4500, NULL),

    -- 25%
    ('FREE', '옵션권 2장', 'OPTION_TICKET', 2, 4501, 7000, NULL),

    -- 15%
    ('FREE', '꽝', 'NONE', 0, 7001, 8500, NULL),

    -- 12%
    ('FREE', '뽑기권 1장', 'MATCHING_TICKET', 1, 8501, 9700, NULL),

    -- 3%
    ('FREE', '풀세트', 'FULL_SET', 0, 9701, 10000, NULL);

INSERT INTO comatching_item.roulette_reward
(
    roulette_type,
    reward_name,
    reward_type,
    quantity,
    range_start,
    range_end,
    remaining_count
)
VALUES
    ('SPECIAL', '옵션권 2장', 'OPTION_TICKET', 2, 1, 3900, NULL),
    ('SPECIAL', '옵션권 5장', 'OPTION_TICKET', 5, 3901, 6400, NULL),
    ('SPECIAL', '뽑기권 1장', 'MATCHING_TICKET', 1, 6401, 8400, NULL),
    ('SPECIAL', '풀세트', 'FULL_SET', 0, 8401, 9400, NULL),
    ('SPECIAL', '뽑기권 5장', 'MATCHING_TICKET', 5, 9401, 9600, NULL),
    ('SPECIAL', '뽑기권 10장', 'MATCHING_TICKET', 10, 9601, 9750, NULL),
    ('SPECIAL', '1만원권 상품권', 'GIFT_CARD', 0, 9751, 9900, 999),
    ('SPECIAL', '2만원권 상품권', 'GIFT_CARD', 0, 9901, 10000, 999);
