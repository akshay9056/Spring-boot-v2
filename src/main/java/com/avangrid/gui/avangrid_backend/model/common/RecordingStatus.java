package com.avangrid.gui.avangrid_backend.model.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordingStatus {

    String username;
    String date;
    String fileName;
    String status;
    String reason;
}


