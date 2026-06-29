package com.lakeon.iceberg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.LakebaseCdfStreamEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IcebergTableBootstrapServiceTest {

    @Test
    void ensureTableStoresObsBackedTableAndMetadataLocations() throws Exception {
        LakeonProperties properties = new LakeonProperties();
        properties.getObs().setBucket("lakeon-test");
        IcebergTableBootstrapService service = new IcebergTableBootstrapService(new ObjectMapper(), properties);
        Connection connection = mock(Connection.class);
        PreparedStatement columns = mock(PreparedStatement.class);
        PreparedStatement insert = mock(PreparedStatement.class);
        ResultSet columnRows = mock(ResultSet.class);
        LakebaseCdfStreamEntity stream = stream();

        when(connection.prepareStatement(contains("information_schema.columns"))).thenReturn(columns);
        when(connection.prepareStatement(contains("INSERT INTO _lakeon_iceberg.tables"))).thenReturn(insert);
        when(columns.executeQuery()).thenReturn(columnRows);
        when(columnRows.next()).thenReturn(true, false);
        when(columnRows.getString("column_name")).thenReturn("id");

        service.ensureTable(connection, stream);

        String expectedLocation = "obs://lakeon-test/lakeon-managed/iceberg/tenant_123/db_123/br_main/public/orders_cdf";
        verify(insert).setString(6, expectedLocation);
        verify(insert).setString(7, expectedLocation + "/metadata/lakeon-lazy-00000.metadata.json");
        ArgumentCaptor<String> metadataJson = ArgumentCaptor.forClass(String.class);
        verify(insert).setString(org.mockito.ArgumentMatchers.eq(8), metadataJson.capture());
        assertThat(metadataJson.getValue()).contains("\"location\":\"" + expectedLocation + "\"");
        verify(insert).setNull(10, Types.BIGINT);
        verify(insert).executeUpdate();
    }

    private static LakebaseCdfStreamEntity stream() {
        LakebaseCdfStreamEntity stream = new LakebaseCdfStreamEntity();
        stream.setTenantId("tenant_123");
        stream.setDatabaseId("db_123");
        stream.setBranchId("br_main");
        stream.setSourceSchema("public");
        stream.setSourceTable("orders");
        stream.setTargetNamespace("public");
        stream.setTargetTable("orders_cdf");
        return stream;
    }
}
