package com.lakeon.lakebasefs;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LakebaseFSFolderService {

    private final LakebaseFSFolderRepository repo;

    public LakebaseFSFolderService(LakebaseFSFolderRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<LakebaseFSFolderEntity> list(TenantEntity tenant) {
        return repo.findByTenantIdOrderByDisplayNameAsc(tenant.getId());
    }

    @Transactional(readOnly = true)
    public LakebaseFSFolderEntity get(TenantEntity tenant, String id) {
        return repo.findByTenantIdAndId(tenant.getId(), id)
                .orElseThrow(() -> new NotFoundException("LakebaseFS folder not found"));
    }

    @Transactional
    public LakebaseFSFolderEntity create(TenantEntity tenant, LakebaseFSFolderProfile profile) {
        repo.findByTenantIdAndDisplayName(tenant.getId(), profile.displayName())
                .ifPresent(existing -> {
                    throw new BadRequestException("LakebaseFS folder already exists: " + existing.getDisplayName());
                });
        LakebaseFSFolderEntity e = new LakebaseFSFolderEntity();
        e.setTenantId(tenant.getId());
        applyProfile(e, profile);
        return repo.save(e);
    }

    @Transactional
    public LakebaseFSFolderEntity update(TenantEntity tenant, String id, LakebaseFSFolderProfile profile) {
        LakebaseFSFolderEntity e = get(tenant, id);
        repo.findByTenantIdAndDisplayName(tenant.getId(), profile.displayName())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new BadRequestException("LakebaseFS folder already exists: " + existing.getDisplayName());
                });
        applyProfile(e, profile);
        return repo.save(e);
    }

    private static void applyProfile(LakebaseFSFolderEntity e, LakebaseFSFolderProfile profile) {
        e.setDisplayName(profile.displayName());
        e.setDirectoryKind(profile.directoryKind());
        e.setStoragePolicy(profile.storagePolicy());
        e.setProcessingProfile(profile.processingProfile());
    }
}
