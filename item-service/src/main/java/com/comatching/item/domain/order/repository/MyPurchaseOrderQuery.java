package com.comatching.item.domain.order.repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.comatching.common.dto.response.PagingResponse;
import com.comatching.item.domain.order.dto.MyPurchaseOrder;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class MyPurchaseOrderQuery {
	public enum Scope { ACTIVE, ALL }

	private final JdbcClient jdbc;

	// One order per row, including bundles. Use the same classification for display, filtering and count.
	private static final String ORDERS = """
		FROM (
			SELECT id, requested_item_name, product_quantity, expected_price, requested_at, expires_at, requester_real_name,
				CASE
					WHEN refund_status = 'COMPLETED' THEN 'REFUNDED'
					WHEN refund_status = 'IN_PROGRESS' THEN 'REFUNDING'
					WHEN status IN ('REJECTED','CANCELED','EXPIRED') THEN status
					WHEN grant_status = 'REVIEW_REQUIRED' THEN 'REVIEW_REQUIRED'
					WHEN status = 'PENDING' AND expires_at <= :now THEN 'EXPIRED'
					WHEN status = 'PENDING' AND grant_status = 'NOT_REQUESTED' THEN 'AWAITING_DEPOSIT'
					WHEN status = 'APPROVED' AND grant_status = 'GRANTED' THEN 'GRANTED'
					WHEN status = 'APPROVED' AND grant_status = 'PENDING' THEN 'GRANTING'
					WHEN status = 'APPROVED' AND grant_status = 'FAILED' THEN 'GRANT_DELAYED'
					ELSE 'REVIEW_REQUIRED'
				END AS user_status
			FROM purchase_order WHERE member_id = :memberId
		) orders_for_member
		""";
	private static final String ACTIVE = " WHERE user_status IN ('AWAITING_DEPOSIT','GRANTING','GRANT_DELAYED','REVIEW_REQUIRED','REFUNDING') ";
	private static final String COLUMNS = """
		SELECT id, requested_item_name, product_quantity, expected_price, requested_at, expires_at, user_status,
			CASE WHEN user_status = 'AWAITING_DEPOSIT' THEN requester_real_name END AS depositor_name
		""";

	@Transactional(readOnly = true)
	public PagingResponse<MyPurchaseOrder> find(long memberId, Scope scope, Pageable page) {
		var params = Map.of("memberId", memberId, "now", LocalDateTime.now());
		boolean active = scope == Scope.ACTIVE;
		String from = ORDERS + (active ? ACTIVE : "");
		String priority = active ? "CASE WHEN user_status = 'AWAITING_DEPOSIT' THEN 0 ELSE 1 END, " : "";
		String sort = " ORDER BY " + priority + "requested_at DESC, id DESC LIMIT :size OFFSET :offset";
		var zone = ZoneId.systemDefault(); // Order timestamps are written with LocalDateTime.now() in this service.
		var rows = jdbc.sql(COLUMNS + from + sort).params(params)
			.param("size", page.getPageSize()).param("offset", page.getOffset())
			.query((rs, row) -> new MyPurchaseOrder(rs.getLong("id"), rs.getString("requested_item_name"),
				rs.getObject("product_quantity", Integer.class), rs.getInt("expected_price"),
				rs.getObject("requested_at", LocalDateTime.class).atZone(zone).toOffsetDateTime(),
				rs.getObject("expires_at", LocalDateTime.class).atZone(zone).toOffsetDateTime(),
				rs.getString("user_status"), rs.getString("depositor_name"))).list();
		return PagingResponse.from(PageableExecutionUtils.getPage(rows, page,
			() -> jdbc.sql("SELECT COUNT(*) " + from).params(params).query(Long.class).single()));
	}
}
