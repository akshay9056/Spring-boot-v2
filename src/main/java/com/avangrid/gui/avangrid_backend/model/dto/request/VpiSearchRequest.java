package com.avangrid.gui.avangrid_backend.model.dto.request;



import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = false)
@Data
public class VpiSearchRequest {
    @JsonProperty(value = "from_date",required = true)
    private String fromDate;
    @JsonProperty(value = "to_date",required = true)
    private String toDate;
    @JsonProperty(required = true)
    private String opco;
    @JsonProperty(required = true)
    private VpiFiltersRequest filters;
    @JsonProperty(required = true)
    private PaginationRequest pagination;
}

