package com.avangrid.gui.avangrid_backend.model.dto.request;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = false)
@Data
public class VpiFiltersRequest {

    @JsonProperty(value = "extensionNum",required = true)
    private List<String> extensionNum;
    @JsonProperty(value = "channelNum",required = true)
    private List<String> channelNum;
    @JsonProperty(value = "aniAliDigits",required = true)
    private List<String> aniAliDigits;
    @JsonProperty(value = "name",required = true)
    private List<String> name;
    @JsonProperty(value = "objectIDs",required = true)
    private List<UUID> objectIDs;
    @JsonProperty(value = "direction",required = true)
    private Boolean direction;
    @JsonProperty(value = "agentID",required = true)
    private List<String> agentID;

}
