package com.avangrid.gui.avangrid_backend.model.dto.request;

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
    @JsonProperty(required = true)
    private String aniAliDigits;
    @JsonProperty(required = true)
    private Integer duration;
    @JsonProperty(required = true)
    private String extensionNum;
    @JsonProperty(required = true)
    private Integer channelNum;
    @JsonProperty(required = true)
    private String objectId;



}
