package com.avangrid.gui.avangrid_backend.model.dto.response;

import com.avangrid.gui.avangrid_backend.model.common.VpiMetadata;
import lombok.Data;
import java.util.List;

@Data
public class VpiSearchResponse {
    private List<VpiMetadata> data;   // array of objects (field → value map)
    private PaginationResponse pagination;
    private String status;
    private String message;
}

