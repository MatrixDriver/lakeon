package com.lakeon.knowledge;

/**
 * Published synchronously inside the createKnowledgeBase transaction.
 * Listeners that need the KB to be committed (e.g. schema seeding)
 * should use {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 */
public class KnowledgeBaseCreatedEvent {
    private final String tenantId;
    private final String kbId;

    public KnowledgeBaseCreatedEvent(String tenantId, String kbId) {
        this.tenantId = tenantId;
        this.kbId = kbId;
    }

    public String getTenantId() { return tenantId; }
    public String getKbId() { return kbId; }
}
