package com.avangrid.gui.avangrid_backend.model.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = false)
@Data
public class PaginationRequest {
    @JsonProperty(required = true)
    private int pageNumber;
    private int pageSize;
}
