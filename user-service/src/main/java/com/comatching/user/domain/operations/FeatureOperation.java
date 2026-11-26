package com.comatching.user.domain.operations;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.comatching.common.operations.Feature;
import com.comatching.common.operations.FeatureState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "feature_operation")
@Getter
@NoArgsConstructor
public class FeatureOperation {
	@Id
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "feature_key", length = 32)
	private Feature feature;

	@Column(nullable = false)
	private boolean openForUse;

	@Column(nullable = false, length = 240)
	private String message;

	private Long changedBy;

	@Column(nullable = false)
	private Instant changedAt;

	@Version
	private Long version;

	FeatureOperation(Feature feature, boolean open) {
		this.feature = feature;
		this.openForUse = open;
		this.message = "";
		this.changedAt = Instant.now();
	}

	void change(boolean open, String message, long actor) {
		this.openForUse = open;
		this.message = message.strip();
		this.changedBy = actor;
		this.changedAt = Instant.now();
	}

	FeatureState publicState() {
		return new FeatureState(feature, openForUse, message, version);
	}
}
