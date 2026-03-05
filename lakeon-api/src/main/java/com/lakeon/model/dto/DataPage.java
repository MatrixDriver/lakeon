package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DataPage(
    List<String> columns,
    List<List<Object>> rows,
    @JsonProperty("total_rows") long totalRows,
    int page,
    @JsonProperty("page_size") int pageSize
) {}
