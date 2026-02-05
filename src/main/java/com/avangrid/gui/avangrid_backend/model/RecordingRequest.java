package com.avangrid.gui.avangrid_backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = false)
@Data
public class RecordingRequest {

    @JsonProperty(required = true)
    private String opco;
    @JsonProperty(required = true)
    private String date;
    @JsonProperty(required = true)
    private String username;

}
