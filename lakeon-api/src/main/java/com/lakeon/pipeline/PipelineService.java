package com.lakeon.pipeline;

import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PipelineService {
    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final PipelineRepository pipelineRepository;
    private final PipelineVersionRepository pipelineVersionRepository;

    public PipelineService(PipelineRepository pipelineRepository,
                           PipelineVersionRepository pipelineVersionRepository) {
        this.pipelineRepository = pipelineRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
    }

    @Transactional
    public PipelineEntity create(String tenantId, String name, String description,
                                  String dataType, String sourceTemplateId, String dagYaml) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Pipeline name is required");
        }
        if (dagYaml == null || dagYaml.isBlank()) {
            throw new BadRequestException("Pipeline dag_yaml is required");
        }

        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setTenantId(tenantId);
        pipeline.setName(name);
        pipeline.setDescription(description);
        pipeline.setDataType(dataType);
        pipeline.setSourceTemplateId(sourceTemplateId);
        pipeline.setLatestVersion(1);
        pipelineRepository.save(pipeline);

        PipelineVersionEntity v1 = new PipelineVersionEntity();
        v1.setPipelineId(pipeline.getId());
        v1.setVersion(1);
        v1.setDagYaml(dagYaml);
        v1.setChangelog("Initial version");
        pipelineVersionRepository.save(v1);

        log.info("Created pipeline {} with v1 for tenant {}", pipeline.getId(), tenantId);
        return pipeline;
    }

    public PipelineEntity get(String tenantId, String pipelineId) {
        // 先查本租户，再查模板（tenant_id='system'）
        return pipelineRepository.findByIdAndTenantId(pipelineId, tenantId)
                .or(() -> pipelineRepository.findById(pipelineId)
                        .filter(p -> Boolean.TRUE.equals(p.getIsTemplate())))
                .orElseThrow(() -> new NotFoundException("Pipeline not found: " + pipelineId));
    }

    public List<PipelineEntity> list(String tenantId) {
        return pipelineRepository.findByTenantIdAndIsTemplateFalseOrderByCreatedAtDesc(tenantId);
    }

    public List<PipelineEntity> listTemplates() {
        return pipelineRepository.findByIsTemplateTrue();
    }

    public List<PipelineVersionEntity> listVersions(String tenantId, String pipelineId) {
        // Verify pipeline exists and belongs to tenant
        get(tenantId, pipelineId);
        return pipelineVersionRepository.findByPipelineIdOrderByVersionDesc(pipelineId);
    }

    public PipelineVersionEntity getVersion(String tenantId, String pipelineId, int version) {
        // Verify pipeline exists and belongs to tenant
        get(tenantId, pipelineId);
        return pipelineVersionRepository.findByPipelineIdAndVersion(pipelineId, version)
                .orElseThrow(() -> new NotFoundException(
                        "Pipeline version not found: " + pipelineId + " v" + version));
    }

    @Transactional
    public PipelineVersionEntity createVersion(String tenantId, String pipelineId,
                                                String dagYaml, String changelog) {
        PipelineEntity pipeline = get(tenantId, pipelineId);
        int nextVersion = pipeline.getLatestVersion() + 1;
        pipeline.setLatestVersion(nextVersion);
        pipelineRepository.save(pipeline);

        PipelineVersionEntity version = new PipelineVersionEntity();
        version.setPipelineId(pipelineId);
        version.setVersion(nextVersion);
        version.setDagYaml(dagYaml);
        version.setChangelog(changelog);
        pipelineVersionRepository.save(version);

        log.info("Created pipeline {} version {} for tenant {}", pipelineId, nextVersion, tenantId);
        return version;
    }

    @Transactional
    public void delete(String tenantId, String pipelineId) {
        PipelineEntity pipeline = get(tenantId, pipelineId);
        // Delete all versions first, then the pipeline
        pipelineVersionRepository.deleteAll(
                pipelineVersionRepository.findByPipelineIdOrderByVersionDesc(pipelineId));
        pipelineRepository.delete(pipeline);
        log.info("Deleted pipeline {} for tenant {}", pipelineId, tenantId);
    }
}
