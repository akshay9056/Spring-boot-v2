package com.avangrid.gui.avangrid_backend.model.entitiybase;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

@MappedSuperclass
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VpiCaptureBase implements VpiCaptureView, Serializable {

    /* ---------- Primary Identifier ---------- */

    @Id
    @Column(name = "objectid", nullable = false)
    private UUID objectId;

    /* ---------- Core Timestamps ---------- */

    @Column(name = "dateadded")
    private OffsetDateTime dateAdded;

    @Column(name = "starttime")
    private OffsetDateTime startTime;

    @Column(name = "gmtstarttime")
    private OffsetDateTime gmtStartTime;

    @Column(name = "classofservicedate")
    private OffsetDateTime classOfServiceDate;

    /* ---------- Identifiers ---------- */

    @Column(name = "resourceid", nullable = false)
    private UUID resourceId;

    @Column(name = "workstationid")
    private UUID workstationId;

    @Column(name = "userid")
    private UUID userId;

    /* ---------- Call Timing ---------- */

    @Column(name = "gmtoffset", nullable = false)
    private Short gmtOffset;

    @Column(name = "duration")
    private Integer duration;

    /* ---------- Triggering ---------- */

    @Column(name = "triggeredbyresourcetypeid")
    private UUID triggeredByResourceTypeId;

    @Column(name = "triggeredbyobjectid")
    private UUID triggeredByObjectId;

    /* ---------- Flags / Meta ---------- */

    @Column(name = "flagid")
    private Short flagId;

    @Column(name = "tags")
    private String tags;

    @Column(name = "sensitivitylevel")
    private Short sensitivityLevel;

    @Column(name = "clientid")
    private Short clientId;

    /* ---------- Channel Info ---------- */

    @Column(name = "channelnum")
    private Short channelNum;

    @Column(name = "channelname")
    private String channelName;

    @Column(name = "extensionnum")
    private String extensionNum;

    @Column(name = "agentid")
    private String agentId;

    @Column(name = "pbxdnis")
    private String pbxDnis;

    @Column(name = "anialidigits")
    private String anialidigits;

    @Column(name = "direction")
    private Boolean direction;

    /* ---------- Media ---------- */

    @Column(name = "mediafileid")
    private UUID mediaFileId;

    @Column(name = "mediamanagerid")
    private UUID mediaManagerId;

    @Column(name = "mediaretention")
    private String mediaRetention;

    /* ---------- Call Linking ---------- */

    @Column(name = "callid")
    private String callId;

    @Column(name = "previouscallid")
    private String previousCallId;

    @Column(name = "globalcallid")
    private String globalCallId;

    /* ---------- Service / Transcript ---------- */

    @Column(name = "classofservice")
    private Integer classOfService;

    @Column(name = "xplatformref")
    private String xPlatformRef;

    @Column(name = "transcriptresult", nullable = false)
    private Short transcriptResult;

    @Column(name = "warehouseobjectkey")
    private Long warehouseObjectKey;

    @Column(name = "transcriptstatus", nullable = false)
    private Short transcriptStatus;

    @Column(name = "audiochannels")
    private Short audioChannels;

    @Column(name = "hastalkover")
    private Boolean hasTalkover;

    /* ---------- VpiCaptureView ---------- */



    // + remaining columns once, here only
}

