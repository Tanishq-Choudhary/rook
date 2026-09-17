package dev.rook.control.budget;

import dev.rook.core.error.RookException;
import dev.rook.core.id.Ids;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public class BudgetService {

    public record Caps(long monthlyMicros, long jobMicros) {

        public Caps {
            if (monthlyMicros < 0 || jobMicros < 0) {
                throw new IllegalArgumentException("caps must not be negative");
            }
        }
    }

    public record Reservation(
            UUID id, UUID installationId, UUID jobId, SpendKind kind, long micros) {}

    public record Usage(
            Instant monthStart,
            long spentMicros,
            long reservedMicros,
            long monthlyCapMicros,
            Map<SpendKind, Long> spentByKind) {

        public long remainingMicros() {
            return Math.max(0, monthlyCapMicros - spentMicros - reservedMicros);
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Caps defaults;

    public BudgetService(JdbcClient jdbc, TransactionTemplate tx, Clock clock, Caps defaults) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.defaults = defaults;
    }

    public void setBudget(UUID installationId, Caps caps) {
        jdbc.sql(
                        """
                        insert into budget (installation_id, monthly_cap_micros, job_cap_micros, updated_at)
                        values (:installation, :monthly, :job, :now)
                        on conflict (installation_id) do update
                          set monthly_cap_micros = excluded.monthly_cap_micros,
                              job_cap_micros = excluded.job_cap_micros,
                              updated_at = excluded.updated_at
                        """)
                .param("installation", installationId)
                .param("monthly", caps.monthlyMicros())
                .param("job", caps.jobMicros())
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    public Caps caps(UUID installationId) {
        return jdbc.sql(
                        "select monthly_cap_micros, job_cap_micros from budget where installation_id = :id")
                .param("id", installationId)
                .query((rs, row) -> new Caps(rs.getLong(1), rs.getLong(2)))
                .optional()
                .orElse(defaults);
    }

    public Reservation reserve(UUID installationId, UUID jobId, SpendKind kind, long micros) {
        if (micros < 0) {
            throw new IllegalArgumentException("an amount must not be negative");
        }
        return tx.execute(
                status -> {
                    lock(installationId);
                    Caps caps = caps(installationId);
                    long month = monthSpend(installationId) + openReservations(installationId);
                    if (month + micros > caps.monthlyMicros()) {
                        throw new BudgetExceededException(
                                BudgetExceededException.Scope.MONTHLY,
                                "the monthly budget of "
                                        + caps.monthlyMicros()
                                        + " micros leaves no room for "
                                        + micros);
                    }
                    if (jobId != null) {
                        long job = jobSpend(jobId) + openReservationsOfJob(jobId);
                        if (job + micros > caps.jobMicros()) {
                            throw new BudgetExceededException(
                                    BudgetExceededException.Scope.JOB,
                                    "the job budget of "
                                            + caps.jobMicros()
                                            + " micros leaves no room for "
                                            + micros);
                        }
                    }
                    UUID id = Ids.newId();
                    jdbc.sql(
                                    """
                                    insert into budget_reservation (id, installation_id, job_id, kind, micros, created_at)
                                    values (:id, :installation, :job, :kind, :micros, :now)
                                    """)
                            .param("id", id)
                            .param("installation", installationId)
                            .param("job", jobId)
                            .param("kind", kind.name())
                            .param("micros", micros)
                            .param("now", Timestamp.from(clock.instant()))
                            .update();
                    return new Reservation(id, installationId, jobId, kind, micros);
                });
    }

    public boolean settle(UUID reservationId, long actualMicros, long units) {
        if (actualMicros < 0 || units < 0) {
            throw new IllegalArgumentException("an amount must not be negative");
        }
        Boolean settled =
                tx.execute(
                        status -> {
                            Optional<Reservation> reservation = openReservation(reservationId);
                            if (reservation.isEmpty()) {
                                return false;
                            }
                            Reservation r = reservation.get();
                            jdbc.sql(
                                            "update budget_reservation set state = 'SETTLED' where id = :id")
                                    .param("id", reservationId)
                                    .update();
                            record(r.installationId(), r.jobId(), r.kind(), units, actualMicros);
                            return true;
                        });
        return Boolean.TRUE.equals(settled);
    }

    public boolean release(UUID reservationId) {
        return jdbc.sql(
                                "update budget_reservation set state = 'RELEASED' where id = :id and state = 'RESERVED'")
                        .param("id", reservationId)
                        .update()
                > 0;
    }

    public int releaseOlderThan(Duration age) {
        return jdbc.sql(
                        "update budget_reservation set state = 'RELEASED' where state = 'RESERVED' and created_at < :cutoff")
                .param("cutoff", Timestamp.from(clock.instant().minus(age)))
                .update();
    }

    public void record(UUID installationId, UUID jobId, SpendKind kind, long units, long micros) {
        jdbc.sql(
                        """
                        insert into usage_ledger (installation_id, job_id, kind, units, cost_micros, at)
                        values (:installation, :job, :kind, :units, :micros, :now)
                        """)
                .param("installation", installationId)
                .param("job", jobId)
                .param("kind", kind.name())
                .param("units", units)
                .param("micros", micros)
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    public Usage usage(UUID installationId) {
        Instant start = monthStart();
        Map<SpendKind, Long> byKind = new EnumMap<>(SpendKind.class);
        for (SpendKind kind : SpendKind.values()) {
            byKind.put(kind, 0L);
        }
        jdbc.sql(
                        """
                        select kind, coalesce(sum(cost_micros), 0) from usage_ledger
                        where installation_id = :installation and at >= :start group by kind
                        """)
                .param("installation", installationId)
                .param("start", Timestamp.from(start))
                .query((rs, row) -> Map.entry(SpendKind.valueOf(rs.getString(1)), rs.getLong(2)))
                .list()
                .forEach(entry -> byKind.put(entry.getKey(), entry.getValue()));
        long spent = byKind.values().stream().mapToLong(Long::longValue).sum();
        return new Usage(
                start,
                spent,
                openReservations(installationId),
                caps(installationId).monthlyMicros(),
                byKind);
    }

    public long jobSpend(UUID jobId) {
        return jdbc.sql(
                        "select coalesce(sum(cost_micros), 0) from usage_ledger where job_id = :job")
                .param("job", jobId)
                .query(Long.class)
                .single();
    }

    private void lock(UUID installationId) {
        jdbc.sql("select id from installation where id = :id for update")
                .param("id", installationId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> RookException.notFound("installation", installationId));
    }

    private long monthSpend(UUID installationId) {
        return jdbc.sql(
                        """
                        select coalesce(sum(cost_micros), 0) from usage_ledger
                        where installation_id = :installation and at >= :start
                        """)
                .param("installation", installationId)
                .param("start", Timestamp.from(monthStart()))
                .query(Long.class)
                .single();
    }

    private long openReservations(UUID installationId) {
        return jdbc.sql(
                        """
                        select coalesce(sum(micros), 0) from budget_reservation
                        where installation_id = :installation and state = 'RESERVED'
                        """)
                .param("installation", installationId)
                .query(Long.class)
                .single();
    }

    private long openReservationsOfJob(UUID jobId) {
        return jdbc.sql(
                        """
                        select coalesce(sum(micros), 0) from budget_reservation
                        where job_id = :job and state = 'RESERVED'
                        """)
                .param("job", jobId)
                .query(Long.class)
                .single();
    }

    private Optional<Reservation> openReservation(UUID id) {
        return jdbc.sql(
                        """
                        select id, installation_id, job_id, kind, micros from budget_reservation
                        where id = :id and state = 'RESERVED' for update
                        """)
                .param("id", id)
                .query(
                        (rs, row) ->
                                new Reservation(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("installation_id", UUID.class),
                                        rs.getObject("job_id", UUID.class),
                                        SpendKind.valueOf(rs.getString("kind")),
                                        rs.getLong("micros")))
                .optional();
    }

    private Instant monthStart() {
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        return today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
