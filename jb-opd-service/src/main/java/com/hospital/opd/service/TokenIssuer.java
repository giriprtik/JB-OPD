package com.hospital.opd.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Issues the day's next OPD token in ONE statement: the upsert takes the counter row's lock and RETURNING
 * hands back the value, so there is no second read and no gap where another desk could interleave.
 * The row lock is held until the caller's transaction commits (a few ms), which is fine at OPD volumes.
 */
@Component
public class TokenIssuer {

    static final String NEXT_TOKEN_SQL = """
            INSERT INTO daily_token_counters (counter_date, last_value) VALUES (?, 1)
            ON CONFLICT (counter_date) DO UPDATE SET last_value = daily_token_counters.last_value + 1
            RETURNING last_value
            """;

    private final JdbcTemplate jdbc;

    public TokenIssuer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside the transaction that saves the visit, so the token and the visit commit together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int next(LocalDate day) {
        Integer value = jdbc.queryForObject(NEXT_TOKEN_SQL, Integer.class, day);
        if (value == null) throw new IllegalStateException("Token counter returned no value");
        return value;
    }
}
