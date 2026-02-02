package com.avangrid.gui.avangrid_backend.model;


import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class VpiMetadata {

    private UUID objectId;
    private LocalDateTime dateAdded;
    private UUID userId;
    private LocalDateTime startTime;
    private Integer duration;
    private String tags;
    private String channelName;
    private String callId;
    private String userName;
    private String aniAlidigts;
    private String extensionNum;
    private String direction;
    private String opco;
}

