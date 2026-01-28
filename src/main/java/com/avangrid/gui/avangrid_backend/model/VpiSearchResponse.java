package com.avangrid.gui.avangrid_backend.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class VpiSearchResponse {
    private List<VpiMetadata> data;   // array of objects (field → value map)
    private PaginationResponse pagination;
    private String status;
    private String message;
}

