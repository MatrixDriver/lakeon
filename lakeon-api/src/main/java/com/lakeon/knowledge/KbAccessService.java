package com.lakeon.knowledge;

import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;

@Service
public class KbAccessService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbShareRepository kbShareRepository;

    public KbAccessService(KnowledgeBaseRepository knowledgeBaseRepository,
                           KbShareRepository kbShareRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.kbShareRepository = kbShareRepository;
    }

    public KbRole checkAccess(String kbId, String tenantId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
        return checkAccess(kb, tenantId);
    }

    public KbRole checkAccess(KnowledgeBaseEntity kb, String tenantId) {
        if (kb.getTenantId().equals(tenantId)) {
            return KbRole.ADMIN;
        }
        return kbShareRepository.findByKbIdAndTenantId(kb.getId(), tenantId)
                .map(KbShareEntity::getRole)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kb.getId()));
    }

    public KnowledgeBaseEntity getKbWithAccess(String kbId, String tenantId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
        checkAccess(kb, tenantId);
        return kb;
    }

    public KnowledgeBaseEntity getKbAdminOnly(String kbId, String tenantId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + kbId));
        KbRole role = checkAccess(kb, tenantId);
        if (role != KbRole.ADMIN) {
            throw new NotFoundException("Knowledge base not found: " + kbId);
        }
        return kb;
    }
}
