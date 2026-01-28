package com.avangrid.gui.avangrid_backend.model;

import lombok.Data;

@Data
public class PaginationRequest {
    private int pageNumber;
    private int pageSize;
}
