package com.avangrid.gui.avangrid_backend.model.entitiybase;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface VpiCaptureView {

    /* ---------- Primary & Technical IDs ---------- */

    UUID getObjectId();
    OffsetDateTime getDateAdded();
    UUID getResourceId();
    UUID getWorkstationId();

    /* ---------- User / Relationship ---------- */

    UUID getUserId();


    /* ---------- Time Related ---------- */

    OffsetDateTime getStartTime();
    Short getGmtOffset();
    OffsetDateTime getGmtStartTime();
    Integer getDuration();

    /* ---------- Trigger & Classification ---------- */

    UUID getTriggeredByResourceTypeId();
    UUID getTriggeredByObjectId();
    Short getFlagId();
    String getTags();
    Short getSensitivityLevel();
    Short getClientId();

    /* ---------- Channel & Extension ---------- */

    Short getChannelNum();
    String getChannelName();
    String getExtensionNum();
    String getAgentId();
    String getPbxDnis();
    String getAnialidigits();
    Boolean getDirection();

    /* ---------- Media ---------- */

    UUID getMediaFileId();
    UUID getMediaManagerId();
    String getMediaRetention();

    /* ---------- Call Tracking ---------- */

    String getCallId();
    String getPreviousCallId();
    String getGlobalCallId();

    /* ---------- Platform & Service ---------- */

    Integer getClassOfService();
    OffsetDateTime getClassOfServiceDate();
    String getXPlatformRef();

    /* ---------- Transcription & Audio ---------- */

    Short getTranscriptResult();
    Long getWarehouseObjectKey();
    Short getTranscriptStatus();
    Short getAudioChannels();
    Boolean getHasTalkover();
}
