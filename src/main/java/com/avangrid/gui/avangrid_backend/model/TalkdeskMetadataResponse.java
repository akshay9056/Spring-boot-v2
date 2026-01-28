package com.avangrid.gui.avangrid_backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TalkdeskMetadataResponse {
    
    private List<Map<String, Object>> data;
    private String continuationToken;
    private Integer totalCount;
}