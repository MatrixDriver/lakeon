package com.lakeon.memory;

import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class MemoryService {

    private final MemoryBaseRepository repository;

    public MemoryService(MemoryBaseRepository repository) {
        this.repository = repository;
    }

    public List<MemoryBaseEntity> listBases(String tenantId) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public MemoryBaseEntity getBase(String tenantId, String id) {
        return repository.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new NotFoundException("Memory base not found: " + id));
    }

    public MemoryBaseEntity createBase(String tenantId, String name, String description,
                                        MemoryBaseType type, String embeddingModel) {
        var entity = new MemoryBaseEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setType(type);
        entity.setEmbeddingModel(embeddingModel != null ? embeddingModel : "BAAI/bge-m3");
        entity.setStatus("READY");
        entity = repository.save(entity);
        return entity;
    }

    public void deleteBase(String tenantId, String id) {
        var entity = getBase(tenantId, id);
        repository.delete(entity);
    }
}
