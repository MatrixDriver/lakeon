package com.lakeon.service;

import com.lakeon.model.dto.PitrRequest;
import com.lakeon.model.dto.PitrResponse;
import com.lakeon.model.dto.PitrWindow;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Recovery operations for databases: Point-In-Time Restore (PITR) and related flows.
 *
 * <p>PITR creates a new Neon timeline branched from the source timeline at the LSN
 * corresponding to {@code targetTime}, then registers a new {@link DatabaseEntity}
 * pointing at the branched timeline. The original database remains untouched
 * ("new-branch" semantics — no in-place rewind).
 */
@Service
public class RecoveryService {

    private static final DateTimeFormatter RESTORED_SUFFIX_FMT =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final DatabaseRepository databaseRepository;
    private final NeonApiClient neonApiClient;
    private final DatabaseService databaseService;

    public RecoveryService(DatabaseRepository databaseRepository,
                           NeonApiClient neonApiClient,
                           DatabaseService databaseService) {
        this.databaseRepository = databaseRepository;
        this.neonApiClient = neonApiClient;
        this.databaseService = databaseService;
    }

    /**
     * Point-In-Time Restore: branch the source database's timeline at the LSN
     * corresponding to {@code request.targetTime()}, register a new database
     * entity that points at the branched timeline, and return both IDs.
     *
     * <p>The Neon pageserver APIs ({@code getLsnByTimestamp} / {@code createBranch})
     * operate on the Neon tenant ID, not the Lakeon tenant ID.
     *
     * <p>The source database must belong to {@code tenant}; otherwise a
     * {@link NotFoundException} is thrown (we do not leak existence of databases
     * owned by other tenants).
     */
    public PitrResponse pitr(TenantEntity tenant, String dbId, PitrRequest request) {
        DatabaseEntity src = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        // Enforce window: target_time must be within [createdAt, now]. Neon's
        // get_lsn_by_timestamp silently clamps out-of-range targets to the earliest
        // available LSN, which would silently restore "earlier than creation".
        Instant target = request.targetTime();
        Instant now = Instant.now();
        if (src.getCreatedAt() != null && target.isBefore(src.getCreatedAt())) {
            throw new BadRequestException("target_time " + target
                + " is before database created_at " + src.getCreatedAt());
        }
        if (target.isAfter(now.plusSeconds(5))) {
            throw new BadRequestException("target_time " + target + " is in the future");
        }

        String lsn = neonApiClient.getLsnByTimestamp(
            src.getNeonTenantId(), src.getNeonTimelineId(), target);

        String newTimelineId = UUID.randomUUID().toString().replace("-", "");
        NeonApiClient.CreateBranchResponse branch = neonApiClient.createBranch(
            src.getNeonTenantId(),
            new NeonApiClient.CreateBranchRequest(src.getNeonTimelineId(), lsn, newTimelineId)
        );

        String newDbName = request.newDbName() != null
            ? request.newDbName()
            : src.getName() + "_restored_" + RESTORED_SUFFIX_FMT.format(Instant.now());

        DatabaseEntity recovered = databaseService.registerRecoveredDatabase(
            src.getTenantId(), src.getNeonTenantId(), branch.timelineId(), newDbName,
            src.getDbUser(), src.getDbPassword(), src.getName());

        return new PitrResponse(
            recovered.getId(),
            branch.timelineId(),
            branch.lsn(),
            null,
            "ready"
        );
    }

    /**
     * Return the PITR window in which a database can be restored.
     *
     * <p>{@code earliest} is the database's {@code createdAt} — a database cannot be
     * restored to a point before it existed. (Neon also has a GC cutoff LSN below which
     * pageserver data is unavailable, but Neon does not expose an LSN→timestamp mapping
     * so we cannot translate that LSN into a wall-clock instant; for the first
     * iteration we ignore it and use createdAt as the lower bound.)
     *
     * <p>{@code latest} is now (wall clock) and {@code latestLsn} is the timeline's
     * head LSN ({@code last_record_lsn}). {@code earliestLsn} is the timeline's
     * {@code latest_gc_cutoff_lsn} — the earliest LSN that can still be queried;
     * PITR earlier than this point would fail because pageserver data has been
     * garbage-collected.
     *
     * <p>The database must belong to {@code tenant}; otherwise a
     * {@link NotFoundException} is thrown.
     */
    public PitrWindow getPitrWindow(TenantEntity tenant, String dbId) {
        DatabaseEntity db = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        NeonApiClient.TimelineInfo info = neonApiClient.getTimelineInfo(
            db.getNeonTenantId(), db.getNeonTimelineId());

        return new PitrWindow(
            db.getCreatedAt(),
            Instant.now(),
            info.latestGcCutoffLsn(),
            info.lastRecordLsn()
        );
    }
}
