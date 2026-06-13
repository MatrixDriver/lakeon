package com.lakeon.agentfs;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AgentFSFolderService {

    private final AgentFSFolderRepository repo;

    public AgentFSFolderService(AgentFSFolderRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<AgentFSFolderEntity> list(TenantEntity tenant) {
        return repo.findByTenantIdOrderByDisplayNameAsc(tenant.getId());
    }

    @Transactional(readOnly = true)
    public AgentFSFolderEntity get(TenantEntity tenant, String id) {
        return repo.findByTenantIdAndId(tenant.getId(), id)
                .orElseThrow(() -> new NotFoundException("agentfs folder not found"));
    }

    @Transactional
    public AgentFSFolderEntity create(TenantEntity tenant, AgentFSFolderProfile profile) {
        repo.findByTenantIdAndDisplayName(tenant.getId(), profile.displayName())
                .ifPresent(existing -> {
                    throw new BadRequestException("agentfs folder already exists: " + existing.getDisplayName());
                });
        AgentFSFolderEntity e = new AgentFSFolderEntity();
        e.setTenantId(tenant.getId());
        applyProfile(e, profile);
        return repo.save(e);
    }

    @Transactional
    public AgentFSFolderEntity update(TenantEntity tenant, String id, AgentFSFolderProfile profile) {
        AgentFSFolderEntity e = get(tenant, id);
        repo.findByTenantIdAndDisplayName(tenant.getId(), profile.displayName())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new BadRequestException("agentfs folder already exists: " + existing.getDisplayName());
                });
        applyProfile(e, profile);
        return repo.save(e);
    }

    private static void applyProfile(AgentFSFolderEntity e, AgentFSFolderProfile profile) {
        e.setDisplayName(profile.displayName());
        e.setDirectoryKind(profile.directoryKind());
        e.setStoragePolicy(profile.storagePolicy());
        e.setProcessingProfile(profile.processingProfile());
    }
}
