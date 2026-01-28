package com.avangrid.gui.avangrid_backend.infra.rge.entity;

import com.avangrid.gui.avangrid_backend.infra.rge.entity.VpiUsersRge;

import com.avangrid.gui.avangrid_backend.model.VpiCaptureView;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AccessLevel;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "vpicapturerge")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VpiCaptureRge implements VpiCaptureView {

    @Id
    @Column(name = "objectid", nullable = false)
    private UUID objectId;

    @Column(name = "dateadded")
    private LocalDateTime dateAdded;

    @Column(name = "resourceid")
    private String resourceId;

    @Column(name = "workstationid")
    private String workstationId;

    // ---- Foreign Key ----
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userid")
    private VpiUsersRge user;

    @Column(name = "starttime")
    private LocalDateTime startTime;

    @Column(name = "gmtoffset")
    private Integer gmtOffset;

    @Column(name = "gmtstarttime")
    private LocalDateTime gmtStartTime;

    @Column(name = "duration")
    private Integer duration;

    @Column(name = "triggeredbyresourcetypeid")
    private String triggeredByResourceTypeId;

    @Column(name = "triggeredbyobjectid")
    private String triggeredByObjectId;

    @Column(name = "flagid")
    private String flagId;

    @Column(name = "tags")
    private String tags;

    @Column(name = "sensitivitylevel")
    private String sensitivityLevel;

    @Column(name = "clientid")
    private String clientId;

    @Column(name = "channelnum")
    private Integer channelNum;

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
    private String direction;

    @Column(name = "mediafileid")
    private String mediaFileId;

    @Column(name = "mediamanagerid")
    private String mediaManagerId;

    @Column(name = "mediaretention")
    private String mediaRetention;

    @Column(name = "callid")
    private String callId;

    @Column(name = "previouscallid")
    private String previousCallId;

    @Column(name = "globalcallid")
    private String globalCallId;

    @Column(name = "classofservice")
    private String classOfService;

    @Column(name = "classofservicedate")
    private LocalDateTime classOfServiceDate;

    @Column(name = "xplatformref")
    private String xPlatformRef;

    @Column(name = "transcriptresult")
    private String transcriptResult;

    @Column(name = "warehouseobjectkey")
    private String warehouseObjectKey;

    @Column(name = "transcriptstatus")
    private String transcriptStatus;

    @Column(name = "audiochannels")
    private Integer audioChannels;

    @Column(name = "hastalkover")
    private Boolean hasTalkover;

    @Override
    public UUID getUserId() {
        return user != null ? user.getUserid() : null;
    }

    @Override
    public String getUserName() {
        return user != null ? user.getFullname() : null;
    }
}




