package com.comatching.item.domain.order.repository;

import com.comatching.common.domain.enums.ItemType;
import com.comatching.item.domain.order.dto.FailedGrantResponse;
import com.comatching.item.domain.order.entity.Order;
import com.comatching.item.domain.order.enums.OrderStatus;
import com.comatching.item.domain.order.enums.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("""
            SELECT CASE WHEN COUNT(o) > 0 THEN true ELSE false END
            FROM Order o
            WHERE o.memberId = :memberId
            AND o.status = OrderStatus.PENDING
            AND o.expiresAt > :now
            """)
    boolean existsActivePendingOrder(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    @Query("""
            SELECT CASE WHEN COUNT(o) > 0 THEN true ELSE false END
            FROM Order o
            WHERE o.memberId = :memberId
            AND (
                (o.status = OrderStatus.APPROVED AND o.refundStatus <> RefundStatus.COMPLETED)
                OR (
                    o.status = OrderStatus.PENDING
                    AND o.expiresAt > :now
                )
            )
            """)
    boolean existsApprovedOrActivePendingOrder(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    @Query("""
            SELECT COUNT(o)
            FROM Order o
            WHERE o.memberId = :memberId
            AND o.productCode = :productCode
            AND o.status = OrderStatus.APPROVED
            AND o.refundStatus <> RefundStatus.COMPLETED
            """)
    long countApprovedByMemberIdAndProductCode(
            @Param("memberId") Long memberId,
            @Param("productCode") String productCode
    );

    @Query("""
            SELECT COUNT(o)
            FROM Order o
            WHERE o.memberId = :memberId
            AND o.productCode = :productCode
            AND o.status = OrderStatus.PENDING
            AND o.expiresAt > :now
            """)
    long countActivePendingByMemberIdAndProductCode(
            @Param("memberId") Long memberId,
            @Param("productCode") String productCode,
            @Param("now") LocalDateTime now
    );

    @Query("""
            SELECT COALESCE(SUM(oi.quantity), 0)
            FROM Order o
            JOIN o.orderItems oi
            WHERE o.memberId = :memberId
            AND o.productCode = :productCode
            AND o.status = OrderStatus.APPROVED
            AND o.refundStatus <> RefundStatus.COMPLETED
            AND oi.itemType = :itemType
            """)
    long sumApprovedQuantityByMemberIdAndProductCodeAndItemType(
            @Param("memberId") Long memberId,
            @Param("productCode") String productCode,
            @Param("itemType") ItemType itemType
    );

    @Query("""
            SELECT COALESCE(SUM(oi.quantity), 0)
            FROM Order o
            JOIN o.orderItems oi
            WHERE o.memberId = :memberId
            AND o.productCode = :productCode
            AND o.status = OrderStatus.PENDING
            AND o.expiresAt > :now
            AND oi.itemType = :itemType
            """)
    long sumActivePendingQuantityByMemberIdAndProductCodeAndItemType(
            @Param("memberId") Long memberId,
            @Param("productCode") String productCode,
            @Param("itemType") ItemType itemType,
            @Param("now") LocalDateTime now
    );

    @Query("""
            SELECT COALESCE(SUM(oi.quantity), 0)
            FROM Order o
            JOIN o.orderItems oi
            WHERE o.memberId = :memberId
            AND o.status = OrderStatus.PENDING
            AND o.expiresAt > :now
            AND oi.itemType = :itemType
            """)
    long sumActivePendingQuantityByMemberIdAndItemType(
            @Param("memberId") Long memberId,
            @Param("itemType") ItemType itemType,
            @Param("now") LocalDateTime now
    );

    @Query("""
            SELECT COALESCE(SUM(o.expectedPrice), 0)
            FROM Order o
            WHERE o.memberId = :memberId
            AND o.status = OrderStatus.APPROVED
            AND o.refundStatus <> RefundStatus.COMPLETED
            AND o.decidedAt >= :startAt
            AND o.decidedAt < :endAt
            """)
    long sumApprovedPriceByMemberIdAndDecidedAtBetween(
            @Param("memberId") Long memberId,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt
    );

    List<Order> findAllByStatusOrderByRequestedAtDesc(OrderStatus status);

    Page<Order> findAllByStatusAndExpiresAtAfter(OrderStatus status, LocalDateTime now, Pageable pageable);

    @Query("""
            SELECT o.id, o.requestedItemName, o.status, o.grantStatus, o.grantAttempt
            FROM Order o
            WHERE o.status = OrderStatus.APPROVED AND o.grantStatus = GrantStatus.FAILED
                AND o.refundStatus = RefundStatus.NONE
            ORDER BY o.id DESC
            """)
    Page<FailedGrantResponse> findFailedGrants(Pageable pageable);

    Page<Order> findByRefundStatusOrderByIdDesc(RefundStatus status, Pageable pageable);

    List<Order> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(OrderStatus status, LocalDateTime now);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.orderItems WHERE o.id = :orderId")
    Optional<Order> findByIdWithItems(@Param("orderId") Long orderId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Order o SET o.status = OrderStatus.APPROVED,
                o.grantStatus = GrantStatus.PENDING,
                o.grantAttempt = o.grantAttempt + 1, o.decidedAt = :now
            WHERE o.id = :id AND o.status = OrderStatus.PENDING
                AND o.grantStatus = GrantStatus.NOT_REQUESTED AND o.expiresAt > :now
            """)
    int approveAndQueue(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("""
            SELECT o.id FROM Order o
            WHERE o.status = OrderStatus.APPROVED
                AND o.grantStatus = GrantStatus.PENDING AND o.refundStatus = RefundStatus.NONE ORDER BY o.id
            """)
    List<Long> findPendingGrantIds(Pageable limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("""
            UPDATE Order o SET o.grantStatus = GrantStatus.FAILED
            WHERE o.id = :id AND o.status = OrderStatus.APPROVED
                AND o.grantStatus = GrantStatus.PENDING AND o.grantAttempt = :attempt
                AND o.refundStatus = RefundStatus.NONE
            """)
    int failGrant(@Param("id") Long id, @Param("attempt") int attempt);

    @Transactional
    @Modifying
    @Query("""
            UPDATE Order o SET o.grantStatus = GrantStatus.PENDING,
                o.grantAttempt = o.grantAttempt + 1
            WHERE o.id = :id AND o.status = OrderStatus.APPROVED
                AND o.grantStatus = GrantStatus.FAILED
                AND o.refundStatus = RefundStatus.NONE
            """)
    int retryFailedGrant(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Order o
            SET o.status = :targetStatus,
                o.decidedAt = :decidedAt
            WHERE o.id = :orderId
            AND o.status = :currentStatus
            AND o.expiresAt > :now
            """)
    int updateStatusIfCurrentAndNotExpired(
            @Param("orderId") Long orderId,
            @Param("currentStatus") OrderStatus currentStatus,
            @Param("targetStatus") OrderStatus targetStatus,
            @Param("decidedAt") LocalDateTime decidedAt,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Order o
            SET o.status = OrderStatus.EXPIRED,
                o.decidedAt = :now
            WHERE o.id = :orderId
            AND o.status = :currentStatus
            AND o.expiresAt <= :now
            """)
    int updateStatusToExpiredIfCurrent(
            @Param("orderId") Long orderId,
            @Param("now") LocalDateTime now,
            @Param("currentStatus") OrderStatus currentStatus
    );
}
