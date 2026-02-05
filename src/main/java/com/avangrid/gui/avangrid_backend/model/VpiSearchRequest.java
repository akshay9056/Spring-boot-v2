package com.avangrid.gui.avangrid_backend.model;



import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = false)
@Data
public class VpiSearchRequest {
    @JsonProperty(required = true)
    private String from_date;
    @JsonProperty(required = true)
    private String to_date;
    @JsonProperty(required = true)
    private String opco;
    @JsonProperty(required = true)
    private VpiFiltersRequest filters;
    @JsonProperty(required = true)
    private PaginationRequest pagination;
}

