package com.lakeon.pipeline;

import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PipelineComponentService {
    private static final Logger log = LoggerFactory.getLogger(PipelineComponentService.class);

    private final PipelineComponentRepository componentRepository;
    private final PipelineComponentVersionRepository componentVersionRepository;

    public PipelineComponentService(PipelineComponentRepository componentRepository,
                                     PipelineComponentVersionRepository componentVersionRepository) {
        this.componentRepository = componentRepository;
        this.componentVersionRepository = componentVersionRepository;
    }

    @Transactional
    public PipelineComponentEntity register(String tenantId, String name, String displayName,
                                             String category, String dataType, String description,
                                             String entrypoint, String paramsSchema,
                                             String inputSchema, String outputSchema,
                                             String outputBranches, Boolean requiresGpu,
                                             String requiresModel, String executionMode) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Component name is required");
        }
        if (entrypoint == null || entrypoint.isBlank()) {
            throw new BadRequestException("Component entrypoint is required");
        }

        PipelineComponentEntity component = new PipelineComponentEntity();
        component.setTenantId(tenantId);
        component.setName(name);
        component.setDisplayName(displayName != null ? displayName : name);
        component.setCategory(category);
        component.setDataType(dataType);
        component.setDescription(description);
        component.setLatestVersion(1);
        componentRepository.save(component);

        PipelineComponentVersionEntity v1 = new PipelineComponentVersionEntity();
        v1.setComponentId(component.getId());
        v1.setVersion(1);
        v1.setEntrypoint(entrypoint);
        v1.setParamsSchema(paramsSchema);
        v1.setInputSchema(inputSchema);
        v1.setOutputSchema(outputSchema);
        v1.setOutputBranches(outputBranches);
        v1.setRequiresGpu(requiresGpu != null ? requiresGpu : false);
        v1.setRequiresModel(requiresModel);
        v1.setExecutionMode(executionMode != null ? executionMode : "FUNCTION");
        v1.setStatus("PUBLISHED");
        componentVersionRepository.save(v1);

        log.info("Registered component {} with v1 for tenant {}", component.getId(), tenantId);
        return component;
    }

    public List<PipelineComponentEntity> listAvailable(String tenantId) {
        return componentRepository.findByTenantIdIsNullOrTenantId(tenantId);
    }

    public List<PipelineComponentEntity> listBuiltin() {
        return componentRepository.findByTenantIdIsNull();
    }

    public PipelineComponentEntity get(String componentId) {
        return componentRepository.findById(componentId)
                .orElseThrow(() -> new NotFoundException("Component not found: " + componentId));
    }

    public List<PipelineComponentVersionEntity> listVersions(String componentId) {
        // Verify component exists
        get(componentId);
        return componentVersionRepository.findByComponentIdOrderByVersionDesc(componentId);
    }

    public PipelineComponentVersionEntity getVersion(String componentId, String version) {
        PipelineComponentEntity component = componentRepository.findById(componentId)
                .orElseThrow(() -> new NotFoundException("Component not found: " + componentId));
        int versionNum;
        if ("latest".equalsIgnoreCase(version)) {
            versionNum = component.getLatestVersion();
        } else {
            try {
                versionNum = Integer.parseInt(version);
            } catch (NumberFormatException e) {
                throw new BadRequestException("Invalid version: " + version);
            }
        }
        return componentVersionRepository.findByComponentIdAndVersion(componentId, versionNum)
                .orElseThrow(() -> new NotFoundException(
                        "Component version not found: " + componentId + " v" + versionNum));
    }

    public PipelineComponentVersionEntity getVersion(String componentId, int version) {
        return getVersion(componentId, String.valueOf(version));
    }
}
