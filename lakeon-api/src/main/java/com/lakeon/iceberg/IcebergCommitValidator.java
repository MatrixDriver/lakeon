package com.lakeon.iceberg;

import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.ConflictException;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class IcebergCommitValidator {
    private static final Set<String> SUPPORTED_ACTIONS = Set.of(
            "assert-create",
            "assert-current-schema-id",
            "assert-ref-snapshot-id",
            "assert-last-assigned-field-id"
    );

    public void validateCreate(List<Map<String, Object>> requirements, String currentHash) {
        validateRequirementActions(requirements);
        if (hasText(currentHash)) {
            throw new ConflictException("Iceberg table already exists");
        }
    }

    public void validateUpdate(List<Map<String, Object>> requirements, String currentHash) {
        boolean foundMetadataHash = false;

        for (Map<String, Object> requirement : safeRequirements(requirements)) {
            validateRequirementAction(requirement);
            String expectedHash = metadataHash(requirement);
            if (expectedHash == null) {
                continue;
            }
            foundMetadataHash = true;
            if (!expectedHash.equals(currentHash)) {
                throw new ConflictException("Iceberg table metadata is stale");
            }
        }

        if (!foundMetadataHash) {
            throw new ConflictException("Iceberg table metadata is stale");
        }
    }

    private void validateRequirementActions(List<Map<String, Object>> requirements) {
        for (Map<String, Object> requirement : safeRequirements(requirements)) {
            validateRequirementAction(requirement);
        }
    }

    private void validateRequirementAction(Map<String, Object> requirement) {
        String action = stringValue(requirement, "type");
        if (action == null) {
            action = stringValue(requirement, "action");
        }
        if (action == null) {
            throw new BadRequestException("Iceberg commit requirement action is required");
        }
        if (!SUPPORTED_ACTIONS.contains(action)) {
            throw new BadRequestException("Unsupported Iceberg commit requirement action: " + action);
        }
    }

    private String metadataHash(Map<String, Object> requirement) {
        String currentMetadataHash = stringValue(requirement, "current-metadata-hash");
        if (currentMetadataHash != null) {
            return currentMetadataHash;
        }
        String metadataHash = stringValue(requirement, "metadata-hash");
        if (metadataHash != null) {
            return metadataHash;
        }
        return stringValue(requirement, "expected-metadata-hash");
    }

    private List<Map<String, Object>> safeRequirements(List<Map<String, Object>> requirements) {
        return requirements == null ? List.of() : requirements;
    }

    private String stringValue(Map<String, Object> values, String key) {
        if (values == null) {
            return null;
        }
        Object value = values.get(key);
        if (!(value instanceof String text) || !hasText(text)) {
            return null;
        }
        return text.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
