package com.comatching.item.infra.bankprobe;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.comatching.item.infra.bankprobe.SyntheticDepositController.Batch;
import com.comatching.item.infra.bankprobe.SyntheticDepositController.Deposit;

import javax.sql.DataSource;

@Service
@ConditionalOnProperty(name = "comatching.bank-probe.enabled", havingValue = "true")
public class SyntheticDepositStore {
    private static final String COLUMNS = "sequence_no, amount_krw, occurred_at, description";
    private final JdbcTemplate jdbc;

    public SyntheticDepositStore(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        new ResourceDatabasePopulator(new ClassPathResource("bank-probe/synthetic-deposits.sql")).execute(dataSource);
    }

    @Transactional
    public void accept(Batch batch) {
        long previous = 0;
        for (Deposit deposit : batch.deposits()) {
            if (deposit.sequence() <= previous) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sequences must be strictly increasing");
            }
            previous = deposit.sequence();
        }
        String stream = batch.streamId().toString();
        jdbc.batchUpdate("""
                INSERT INTO synthetic_probe_deposit (stream_id, sequence_no, amount_krw, occurred_at, description)
                VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE amount_krw = amount_krw
                """, batch.deposits(), SyntheticDepositController.PAGE_SIZE, (ps, deposit) -> {
            ps.setString(1, stream);
            ps.setLong(2, deposit.sequence());
            ps.setLong(3, deposit.amountKrw());
            ps.setString(4, deposit.occurredAt().toString());
            ps.setString(5, deposit.description());
        });
        // An unchanged replay is acknowledged. A reused identity with different content
        // rolls back every new row in the batch. Do not acknowledge a partial batch.
        var ids = batch.deposits().stream().map(Deposit::sequence).toList();
        var placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        var parameters = new java.util.ArrayList<Object>();
        parameters.add(stream);
        parameters.addAll(ids);
        var stored = jdbc.query("SELECT " + COLUMNS + " FROM synthetic_probe_deposit WHERE stream_id = ? "
                        + "AND sequence_no IN (" + placeholders + ") ORDER BY sequence_no FOR UPDATE",
                SyntheticDepositStore::mapDeposit, parameters.toArray());
        if (!stored.equals(batch.deposits())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Synthetic transaction identity conflict");
        }
    }

    public List<Deposit> read(UUID stream, long after) {
        return jdbc.query("SELECT " + COLUMNS + " FROM synthetic_probe_deposit WHERE stream_id = ? "
                        + "AND sequence_no > ? ORDER BY sequence_no LIMIT " + SyntheticDepositController.PAGE_SIZE,
                SyntheticDepositStore::mapDeposit, stream.toString(), after);
    }

    private static Deposit mapDeposit(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Deposit(rs.getLong("sequence_no"), "CREDIT", rs.getLong("amount_krw"),
                OffsetDateTime.parse(rs.getString("occurred_at")), rs.getString("description"));
    }
}
