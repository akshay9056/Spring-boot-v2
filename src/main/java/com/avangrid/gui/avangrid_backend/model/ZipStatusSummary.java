package com.avangrid.gui.avangrid_backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ZipStatusSummary {

    private int totalRequests;
    private int success;
    private int failure;
    private List<RecordingStatus> records;

}

