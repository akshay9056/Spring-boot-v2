package com.avangrid.gui.avangrid_backend.model;

import java.util.List;
import lombok.Data;

@Data
public class VpiFiltersRequest {
    private List<String> fileName;
    private List<String> extensionNum;
    private List<String> objectID;
    private List<String> channelNum;
    private List<String> aniAliDigits;
    private List<String> name;
}
