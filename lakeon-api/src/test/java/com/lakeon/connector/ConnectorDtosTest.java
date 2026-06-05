package com.lakeon.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorDtosTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void postgresConnectionSnapshot_doesNotSerializePasswordButKeepsInternalAccessor() throws Exception {
        ConnectorDtos.PostgresConnectionSnapshot snapshot = new ConnectorDtos.PostgresConnectionSnapshot(
            "conn_1",
            "Primary Postgres",
            "localhost",
            5432,
            "app",
            "postgres",
            "secret"
        );

        String json = objectMapper.writeValueAsString(snapshot);

        assertThat(json).doesNotContain("secret");
        assertThat(json).doesNotContain("password");
        assertThat(snapshot.password()).isEqualTo("secret");
    }
}
