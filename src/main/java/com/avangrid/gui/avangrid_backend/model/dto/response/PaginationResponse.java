package com.avangrid.gui.avangrid_backend.model.dto.response;

import lombok.Data;

@Data
public class PaginationResponse {
    private int pageNumber;
    private int pageSize;
    private long totalRecords;
    private int totalPages;
}
