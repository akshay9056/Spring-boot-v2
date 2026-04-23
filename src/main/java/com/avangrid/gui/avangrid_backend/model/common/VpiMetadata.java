package com.avangrid.gui.avangrid_backend.model.common;


import lombok.Data;
import java.util.UUID;

@Data
public class VpiMetadata {

    private UUID objectId;
    private String dateAdded;
    private UUID userId;
    private String startTime;
    private Integer duration;
    private String tags;
    private String channelName;
    private short channelNum;
    private String callId;
    private String username;
    private String aniAliDigits;
    private String extensionNum;
    private boolean direction;
    private String agentId;
    private String opco;
}

