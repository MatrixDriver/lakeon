package com.lakeon.service;

import com.lakeon.model.dto.PitrRequest;
import com.lakeon.model.dto.PitrResponse;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.repository.DatabaseRepository;
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
     */
    public PitrResponse pitr(String dbId, PitrRequest request) {
        DatabaseEntity src = databaseRepository.findById(dbId)
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        String lsn = neonApiClient.getLsnByTimestamp(
            src.getNeonTenantId(), src.getNeonTimelineId(), request.targetTime());

        String newTimelineId = "tl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        NeonApiClient.CreateBranchResponse branch = neonApiClient.createBranch(
            src.getNeonTenantId(),
            new NeonApiClient.CreateBranchRequest(src.getNeonTimelineId(), lsn, newTimelineId)
        );

        String newDbName = request.newDbName() != null
            ? request.newDbName()
            : src.getName() + "_restored_" + RESTORED_SUFFIX_FMT.format(Instant.now());

        DatabaseEntity recovered = databaseService.registerRecoveredDatabase(
            src.getTenantId(), src.getNeonTenantId(), branch.timelineId(), newDbName);

        return new PitrResponse(
            recovered.getId(),
            branch.timelineId(),
            branch.lsn(),
            null,
            "ready"
        );
    }
}
