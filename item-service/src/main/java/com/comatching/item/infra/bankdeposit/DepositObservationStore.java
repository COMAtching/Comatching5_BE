package com.comatching.item.infra.bankdeposit;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import com.comatching.item.infra.bankdeposit.DepositObservationController.Observation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "comatching.deposit-ingest.enabled", havingValue = "true")
public class DepositObservationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    record Entry(String eventId, String payload) {}

    // The immutable observation is also the outbox row. An HTTP receipt is valid after commit.
    @Transactional
    public void accept(List<Observation> observations) {
        var payloads = new TreeMap<String,String>();
        for (var observation : observations) {
            try {
                payloads.put(observation.eventId(), mapper.writeValueAsString(observation));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Cannot serialize deposit observation", e);
            }
        }
        jdbc.batchUpdate("""
            INSERT INTO bank_deposit_observation (event_id, payload) VALUES (?, ?)
            ON DUPLICATE KEY UPDATE event_id = event_id
            """, payloads.entrySet(), 100, (ps, entry) -> {
            ps.setString(1, entry.getKey());
            ps.setString(2, entry.getValue());
        });
        String placeholders = String.join(",", Collections.nCopies(payloads.size(), "?"));
        var stored = jdbc.query("SELECT event_id, payload FROM bank_deposit_observation WHERE event_id IN ("
                + placeholders + ") FOR UPDATE", (rs, i) -> new Entry(rs.getString(1), rs.getString(2)), payloads.keySet().toArray());
        if (stored.size() != payloads.size() || stored.stream().anyMatch(e -> !e.payload().equals(payloads.get(e.eventId())))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
    }

    // Rows with no completed page can be consulted as evidence, but have no spendable ledger ID.
    public List<Observation> recentUnreconciled() {
        // The UUID prefix is ASCII. Compare its bytes even if the two tables use different collations.
        return jdbc.query("""
            SELECT o.payload FROM bank_deposit_observation o WHERE NOT EXISTS
              (SELECT 1 FROM bank_scan_capture c WHERE c.capture_id=LEFT(CAST(o.event_id AS BINARY(96)),36))
            ORDER BY o.received_at DESC,o.event_id DESC LIMIT 30
            """, this::readObservation);
    }

    public List<Observation> recent() {
        return jdbc.query("""
            SELECT payload FROM bank_deposit_observation
            ORDER BY received_at DESC, event_id DESC LIMIT 30
            """, this::readObservation);
    }

    private Observation readObservation(ResultSet rs, int row) throws SQLException {
        try { return mapper.readValue(rs.getString(1), Observation.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read deposit observation",e); }
    }

    @Transactional
    public List<Entry> claim(String lease) {
        var now = Timestamp.from(Instant.now());
        var rows = jdbc.query("""
            SELECT event_id, payload FROM bank_deposit_observation
            WHERE published_at IS NULL AND available_at <= ? ORDER BY available_at, event_id
            LIMIT 50 FOR UPDATE SKIP LOCKED
            """, (rs, i) -> new Entry(rs.getString(1), rs.getString(2)), now);
        if (!rows.isEmpty()) {
            var until = Timestamp.from(now.toInstant().plusSeconds(90));
            jdbc.batchUpdate("UPDATE bank_deposit_observation SET lease_token=?, available_at=? WHERE event_id=?",
                    rows, 50, (ps, e) -> { ps.setString(1, lease); ps.setTimestamp(2, until); ps.setString(3, e.eventId()); });
        }
        return rows;
    }

    public void published(String lease) {
        jdbc.update("UPDATE bank_deposit_observation SET published_at=CURRENT_TIMESTAMP, lease_token=NULL WHERE lease_token=?", lease);
    }
}
