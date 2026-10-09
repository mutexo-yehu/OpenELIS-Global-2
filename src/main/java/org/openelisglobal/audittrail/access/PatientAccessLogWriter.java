package org.openelisglobal.audittrail.access;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.openelisglobal.common.log.LogEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes {@link PatientAccessRecord}s to {@code clinlims.patient_access_log} on
 * a background thread, in batches, so recording a read never slows the screen
 * that made it. If the database falls behind and the queue fills, new records
 * are dropped and counted in the log rather than blocking requests.
 */
@Component
public class PatientAccessLogWriter {

    static final String INSERT_SQL = "INSERT INTO clinlims.patient_access_log (access_time, sys_user_id, login_name,"
            + " http_method, resource, query_string, patient_id, accession_number, client_address, http_status)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final int QUEUE_CAPACITY = 10_000;
    private static final int BATCH_SIZE = 200;

    private final BlockingQueue<PatientAccessRecord> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();

    @Autowired
    private DataSource dataSource;

    @Value("${org.openelisglobal.audit.read.enabled:false}")
    private boolean enabled;

    @Value("${org.openelisglobal.audit.read.retentionDays:0}")
    private int retentionDays;

    private JdbcTemplate jdbcTemplate;
    private Thread worker;
    private volatile boolean running;

    @PostConstruct
    void start() {
        if (!enabled) {
            return;
        }
        jdbcTemplate = new JdbcTemplate(dataSource);
        running = true;
        worker = new Thread(this::drainLoop, "patient-access-log");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Queues a record; never blocks. */
    public void submit(PatientAccessRecord record) {
        if (!queue.offer(record)) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 1000 == 0) {
                LogEvent.logWarn(getClass().getSimpleName(), "submit",
                        "Patient access log queue is full; " + count + " record(s) dropped so far");
            }
        }
    }

    private void drainLoop() {
        List<PatientAccessRecord> batch = new ArrayList<>(BATCH_SIZE);
        while (running || !queue.isEmpty()) {
            try {
                PatientAccessRecord first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                write(batch);
            } catch (InterruptedException e) {
                // stop() was called: write what's left, then exit
                queue.drainTo(batch);
                write(batch);
                return;
            } catch (RuntimeException e) {
                LogEvent.logError("Could not write " + batch.size() + " patient access log record(s)", e);
            } finally {
                batch.clear();
            }
        }
    }

    void write(List<PatientAccessRecord> batch) {
        if (batch.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, batch, batch.size(), (ps, r) -> {
            ps.setTimestamp(1, r.accessTime());
            setNullableInt(ps, 2, r.sysUserId());
            ps.setString(3, r.loginName());
            ps.setString(4, r.httpMethod());
            ps.setString(5, r.resource());
            ps.setString(6, r.queryString());
            setNullableInt(ps, 7, r.patientId());
            ps.setString(8, r.accessionNumber());
            ps.setString(9, r.clientAddress());
            ps.setInt(10, r.httpStatus());
        });
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setInt(index, value);
        }
    }

    /**
     * Deletes records older than
     * {@code org.openelisglobal.audit.read.retentionDays}; 0 keeps them forever.
     */
    @Scheduled(cron = "${org.openelisglobal.audit.read.purgeCron:0 15 3 * * ?}")
    public void purgeExpired() {
        if (!enabled || retentionDays <= 0) {
            return;
        }
        int deleted = jdbcTemplate.update(
                "DELETE FROM clinlims.patient_access_log WHERE access_time < now() - make_interval(days => ?)",
                retentionDays);
        LogEvent.logInfo(getClass().getSimpleName(), "purgeExpired",
                "Removed " + deleted + " patient access log record(s) older than " + retentionDays + " days");
    }
}
