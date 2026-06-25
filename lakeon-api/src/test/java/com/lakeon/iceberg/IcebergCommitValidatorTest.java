package com.lakeon.iceberg;

import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.ConflictException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IcebergCommitValidatorTest {
    private final IcebergCommitValidator validator = new IcebergCommitValidator();

    @Test
    void createSucceedsWhenCurrentHashIsAbsent() {
        assertThatCode(() -> validator.validateCreate(List.of(requirement("assert-create")), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateCreate(List.of(requirement("assert-create")), "  "))
                .doesNotThrowAnyException();
    }

    @Test
    void createThrowsConflictWhenCurrentHashExists() {
        assertThatThrownBy(() -> validator.validateCreate(List.of(requirement("assert-create")), "hash-1"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateSucceedsWhenSupportedRequirementIncludesMatchingMetadataHash() {
        List<Map<String, Object>> requirements = List.of(
                Map.of("type", "assert-current-schema-id", "current-metadata-hash", "hash-1")
        );

        assertThatCode(() -> validator.validateUpdate(requirements, "hash-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void updateThrowsConflictForStaleMetadataHash() {
        List<Map<String, Object>> requirements = List.of(
                Map.of("type", "assert-ref-snapshot-id", "metadata-hash", "hash-1")
        );

        assertThatThrownBy(() -> validator.validateUpdate(requirements, "hash-2"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateThrowsConflictWhenAnyMetadataHashRequirementIsStale() {
        List<Map<String, Object>> requirements = List.of(
                Map.of("type", "assert-current-schema-id", "current-metadata-hash", "hash-2"),
                Map.of("type", "assert-ref-snapshot-id", "metadata-hash", "hash-1")
        );

        assertThatThrownBy(() -> validator.validateUpdate(requirements, "hash-2"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateThrowsConflictWhenNoMetadataHashRequirementIsPresent() {
        List<Map<String, Object>> requirements = List.of(requirement("assert-last-assigned-field-id"));

        assertThatThrownBy(() -> validator.validateUpdate(requirements, "hash-1"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void unsupportedRequirementThrowsBadRequest() {
        assertThatThrownBy(() -> validator.validateUpdate(List.of(requirement("assert-unknown")), "hash-1"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> validator.validateUpdate(List.of(Map.of("type", "  ")), "hash-1"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void actionCanBeReadFromEitherTypeOrAction() {
        List<Map<String, Object>> typeRequirements = List.of(
                Map.of("type", "assert-current-schema-id", "expected-metadata-hash", "hash-1")
        );
        List<Map<String, Object>> actionRequirements = List.of(
                Map.of("action", "assert-current-schema-id", "expected-metadata-hash", "hash-1")
        );

        assertThatCode(() -> validator.validateUpdate(typeRequirements, "hash-1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateUpdate(actionRequirements, "hash-1"))
                .doesNotThrowAnyException();
    }

    private Map<String, Object> requirement(String type) {
        return Map.of("type", type);
    }
}
