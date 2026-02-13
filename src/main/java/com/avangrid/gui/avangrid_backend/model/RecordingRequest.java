package com.avangrid.gui.avangrid_backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = false)
@Data
public class RecordingRequest {

    @JsonProperty(required = true)
    private String opco;
    @JsonProperty(required = true)
    private String date;
    @JsonProperty(required = true)
    private String username;
    @JsonProperty(required = true)
    private String anialidigits;
    @JsonProperty(required = true)
    private Integer duration;
    @JsonProperty(required = true)
    private String extensionNum;
    @JsonProperty(required = true)
    private Integer channelNum;
    @JsonProperty(required = true)
    private String objectId;



}
