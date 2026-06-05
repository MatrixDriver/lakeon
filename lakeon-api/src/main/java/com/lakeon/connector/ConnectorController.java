package com.lakeon.connector;

import com.lakeon.connector.ConnectorDtos.ConnectorResponse;
import com.lakeon.connector.ConnectorDtos.ConnectorTestResponse;
import com.lakeon.connector.ConnectorDtos.CreateConnectorRequest;
import com.lakeon.model.dto.SourceTableInfo;
import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/connectors")
public class ConnectorController {
    private final ConnectorService connectorService;

    public ConnectorController(ConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    @GetMapping
    public List<ConnectorResponse> list(HttpServletRequest request) {
        TenantEntity tenant = currentTenant(request);
        return connectorService.list(tenant.getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectorResponse create(HttpServletRequest request, @RequestBody CreateConnectorRequest body) {
        TenantEntity tenant = currentTenant(request);
        return connectorService.create(tenant.getId(), body);
    }

    @GetMapping("/{id}")
    public ConnectorResponse get(HttpServletRequest request, @PathVariable String id) {
        TenantEntity tenant = currentTenant(request);
        return connectorService.get(tenant.getId(), id);
    }

    @PostMapping("/{id}/test")
    public ConnectorTestResponse test(HttpServletRequest request, @PathVariable String id) {
        TenantEntity tenant = currentTenant(request);
        return connectorService.test(tenant.getId(), id);
    }

    @GetMapping("/{id}/postgres/tables")
    public List<SourceTableInfo> tables(HttpServletRequest request, @PathVariable String id) {
        TenantEntity tenant = currentTenant(request);
        return connectorService.listPostgresTables(tenant.getId(), id);
    }

    private TenantEntity currentTenant(HttpServletRequest request) {
        return (TenantEntity) request.getAttribute("tenant");
    }
}
