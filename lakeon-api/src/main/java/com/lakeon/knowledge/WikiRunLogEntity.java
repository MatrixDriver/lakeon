package com.lakeon.knowledge;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "wiki_run_logs", indexes = {
    @Index(name = "idx_wiki_run_logs_kb_id", columnList = "kb_id"),
    @Index(name = "idx_wiki_run_logs_created_at", columnList = "created_at")
})
public class WikiRunLogEntity {
    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "kb_id", length = 64)
    private String kbId;

    // "ingest" | "curate" | "save_response" | "rebuild"
    @Column(name = "run_type", length = 32)
    private String runType;

    // For ingest: the source document filename
    @Column(name = "trigger_doc", length = 256)
    private String triggerDoc;

    @Column(name = "pages_created")
    private int pagesCreated;

    @Column(name = "pages_updated")
    private int pagesUpdated;

    @Column(name = "pages_deleted")
    private int pagesDeleted;

    @Column(name = "duration_ms")
    private long durationMs;

    // "success" | "error"
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "tool_calls_count")
    private int toolCallsCount;

    @Column(name = "token_count")
    private long tokenCount;

    @Column(name = "source", length = 32)
    private String source;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getRunType() { return runType; }
    public void setRunType(String runType) { this.runType = runType; }
    public String getTriggerDoc() { return triggerDoc; }
    public void setTriggerDoc(String triggerDoc) { this.triggerDoc = triggerDoc; }
    public int getPagesCreated() { return pagesCreated; }
    public void setPagesCreated(int pagesCreated) { this.pagesCreated = pagesCreated; }
    public int getPagesUpdated() { return pagesUpdated; }
    public void setPagesUpdated(int pagesUpdated) { this.pagesUpdated = pagesUpdated; }
    public int getPagesDeleted() { return pagesDeleted; }
    public void setPagesDeleted(int pagesDeleted) { this.pagesDeleted = pagesDeleted; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public int getToolCallsCount() { return toolCallsCount; }
    public void setToolCallsCount(int toolCallsCount) { this.toolCallsCount = toolCallsCount; }
    public long getTokenCount() { return tokenCount; }
    public void setTokenCount(long tokenCount) { this.tokenCount = tokenCount; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
