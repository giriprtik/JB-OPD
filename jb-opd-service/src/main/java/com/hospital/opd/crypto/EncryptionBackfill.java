package com.hospital.opd.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * One-off job: encrypts plaintext left from before field encryption, and re-encrypts values under an old key
 * after a rotation. Runs at startup only with app.crypto.backfill=true; safe to run more than once.
 *
 * Rollout: deploy with allow-plaintext=true, run once with backfill=true, check the counts it logs are 0 on
 * a second run, then redeploy with allow-plaintext=false and backfill=false.
 */
@Component
@ConditionalOnProperty(name = "app.crypto.backfill", havingValue = "true")
public class EncryptionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EncryptionBackfill.class);
    static final int BATCH = 500;

    /** table -> encrypted columns. Table and column names are constants, never user input. */
    static final Map<String, List<String>> COLUMNS = Map.of(
            "visits", List.of("chief_complaint", "diagnosis", "consultation_notes"),
            "patients", List.of("allergies"),
            "vital_signs", List.of("symptoms_brief"));

    private final JdbcTemplate jdbc;
    private final FieldCipher cipher;

    public EncryptionBackfill(JdbcTemplate jdbc, FieldCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        COLUMNS.forEach((table, columns) -> columns.forEach(column -> {
            int rewritten = rewrap(table, column);
            log.info("Encryption backfill: {}.{} rewrote {} value(s)", table, column, rewritten);
        }));
    }

    int rewrap(String table, String column) {
        int rewritten = 0;
        long lastId = 0;
        while (true) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, " + column + " AS v FROM " + table
                            + " WHERE id > ? AND " + column + " IS NOT NULL ORDER BY id LIMIT " + BATCH, lastId);
            if (rows.isEmpty()) return rewritten;
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                String stored = (String) row.get("v");
                lastId = id;
                if (!cipher.needsRewrap(stored)) continue;
                String plain = cipher.isEncrypted(stored) ? cipher.decrypt(stored) : stored;
                // Compare-and-set: skip the row if the app changed it meanwhile (it is already encrypted then).
                rewritten += jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ? AND " + column + " = ?",
                        cipher.encrypt(plain), id, stored);
            }
        }
    }
}
