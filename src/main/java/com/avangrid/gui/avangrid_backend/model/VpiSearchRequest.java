package com.avangrid.gui.avangrid_backend.model;





import lombok.Data;

@Data
public class VpiSearchRequest {
    private String from_date;
    private String to_date;
    private String opco;
    private VpiFiltersRequest filters;
    private PaginationRequest pagination;
}

