package com.avangrid.gui.avangrid_backend.model;


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
    private String callId;
    private String userName;
    private String aniAlidigts;
    private String extensionNum;
    private boolean direction;
    private String agentId;
    private String opco;
}


