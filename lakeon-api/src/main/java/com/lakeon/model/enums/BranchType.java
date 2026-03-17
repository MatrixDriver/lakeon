package com.lakeon.model.enums;

public enum BranchType {
    USER,      // User-created branch
    BACKUP,    // System-created backup (from Restore/Promote)
    SNAPSHOT   // System-created for version snapshot timeline (not shown in UI branch list)
}
