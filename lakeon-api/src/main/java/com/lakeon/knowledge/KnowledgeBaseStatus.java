package com.lakeon.knowledge;

public enum KnowledgeBaseStatus {
    CREATING,   // Database being provisioned
    READY,      // Ready to accept documents
    FAILED      // Database creation failed
}
