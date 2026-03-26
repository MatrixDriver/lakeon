package com.lakeon.knowledge;

import com.lakeon.config.LakeonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DataSourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceSyncService.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        ".pdf", ".epub", ".docx", ".md", ".markdown", ".txt"
    );

    private final DataSourceRepository dsRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeService knowledgeService;
    private final LakeonProperties props;

    public DataSourceSyncService(DataSourceRepository dsRepository,
                                  DocumentRepository documentRepository,
                                  KnowledgeBaseRepository kbRepository,
                                  KnowledgeService knowledgeService,
                                  LakeonProperties props) {
        this.dsRepository = dsRepository;
        this.documentRepository = documentRepository;
        this.kbRepository = kbRepository;
        this.knowledgeService = knowledgeService;
        this.props = props;
    }

    public Map<String, Object> sync(DataSourceEntity ds) {
        if (ds.getStatus() == DataSourceStatus.SYNCING) {
            throw new IllegalStateException("Datasource is already syncing");
        }

        ds.setStatus(DataSourceStatus.SYNCING);
        ds.setError(null);
        dsRepository.save(ds);

        try {
            List<ObsFileInfo> obsFiles = listObsFiles(ds.getObsPrefix());
            log.info("Datasource {} sync: found {} supported files in OBS", ds.getId(), obsFiles.size());

            List<DocumentEntity> existing = documentRepository.findByDatasourceId(ds.getId());
            Map<String, DocumentEntity> existingByKey = existing.stream()
                .collect(Collectors.toMap(DocumentEntity::getObsKey, d -> d));

            Set<String> obsKeys = obsFiles.stream().map(f -> f.key).collect(Collectors.toSet());
            int added = 0, modified = 0, deleted = 0, skipped = 0;
            List<String> toProcessIds = new ArrayList<>();

            KnowledgeBaseEntity kb = kbRepository.findById(ds.getKbId()).orElse(null);
            if (kb == null) throw new RuntimeException("KB not found: " + ds.getKbId());

            for (ObsFileInfo obsFile : obsFiles) {
                DocumentEntity doc = existingByKey.get(obsFile.key);
                if (doc != null) {
                    if (obsFile.etag.equals(doc.getObsEtag())) {
                        skipped++;
                        continue;
                    }
                    doc.setObsEtag(obsFile.etag);
                    doc.setObsSize(obsFile.size);
                    doc.setObsLastModified(obsFile.lastModified);
                    doc.setSizeBytes(obsFile.size);
                    doc.setStatus(DocumentStatus.PENDING);
                    doc.setError(null);
                    doc.setChunksCount(null);
                    documentRepository.save(doc);
                    toProcessIds.add(doc.getId());
                    modified++;
                } else {
                    String filename = obsFile.key.substring(ds.getObsPrefix().length());
                    String format = knowledgeService.detectFormatPublic(filename);
                    DocumentEntity newDoc = new DocumentEntity();
                    newDoc.setTenantId(ds.getTenantId());
                    newDoc.setDatabaseId(kb.getDatabaseId());
                    newDoc.setKbId(ds.getKbId());
                    newDoc.setDatasourceId(ds.getId());
                    newDoc.setFilename(filename);
                    newDoc.setObsKey(obsFile.key);
                    newDoc.setObsEtag(obsFile.etag);
                    newDoc.setObsSize(obsFile.size);
                    newDoc.setObsLastModified(obsFile.lastModified);
                    newDoc.setSizeBytes(obsFile.size);
                    newDoc.setFormat(format);
                    newDoc.setStatus(DocumentStatus.PENDING);
                    documentRepository.save(newDoc);
                    toProcessIds.add(newDoc.getId());
                    added++;
                }
            }

            for (DocumentEntity doc : existing) {
                if (!obsKeys.contains(doc.getObsKey())) {
                    knowledgeService.deleteDocumentInternal(doc);
                    deleted++;
                }
            }

            if (!toProcessIds.isEmpty()) {
                com.lakeon.model.entity.TenantEntity tenant = new com.lakeon.model.entity.TenantEntity();
                tenant.setId(ds.getTenantId());
                knowledgeService.batchProcessDocuments(tenant, toProcessIds);
            }

            Map<String, Object> stats = Map.of(
                "added", added,
                "modified", modified,
                "deleted", deleted,
                "skipped", skipped,
                "total_obs_files", obsFiles.size()
            );

            ds.setStatus(DataSourceStatus.ACTIVE);
            ds.setLastSyncedAt(Instant.now());
            ds.setLastSyncStats(stats);
            ds.setFileCount(obsFiles.size());
            dsRepository.save(ds);

            log.info("Datasource {} sync complete: {}", ds.getId(), stats);
            return stats;

        } catch (Exception e) {
            log.error("Datasource {} sync failed: {}", ds.getId(), e.getMessage(), e);
            ds.setStatus(DataSourceStatus.ERROR);
            ds.setError(e.getMessage());
            dsRepository.save(ds);
            throw new RuntimeException("Sync failed: " + e.getMessage(), e);
        }
    }

    private List<ObsFileInfo> listObsFiles(String prefix) {
        S3Client s3 = buildS3Client();
        try {
            List<ObsFileInfo> files = new ArrayList<>();
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder reqBuilder = ListObjectsV2Request.builder()
                    .bucket(props.getObs().getBucket())
                    .prefix(prefix)
                    .maxKeys(1000);
                if (continuationToken != null) {
                    reqBuilder.continuationToken(continuationToken);
                }
                ListObjectsV2Response resp = s3.listObjectsV2(reqBuilder.build());
                for (S3Object obj : resp.contents()) {
                    if (obj.size() == 0) continue;
                    String ext = obj.key().contains(".")
                        ? obj.key().substring(obj.key().lastIndexOf('.')).toLowerCase()
                        : "";
                    if (SUPPORTED_EXTENSIONS.contains(ext)) {
                        files.add(new ObsFileInfo(
                            obj.key(),
                            obj.eTag().replace("\"", ""),
                            obj.size(),
                            obj.lastModified()
                        ));
                    }
                }
                continuationToken = resp.isTruncated() ? resp.nextContinuationToken() : null;
            } while (continuationToken != null);
            return files;
        } finally {
            s3.close();
        }
    }

    private S3Client buildS3Client() {
        return S3Client.builder()
            .endpointOverride(URI.create(props.getObs().getEndpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
            .region(Region.of("cn-north-4"))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
            .build();
    }

    record ObsFileInfo(String key, String etag, long size, Instant lastModified) {}
}
