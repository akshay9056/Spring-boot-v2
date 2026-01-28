package com.avangrid.gui.avangrid_backend.model;

import java.time.LocalDateTime;
import java.util.UUID;

public interface VpiCaptureView {

    // Primary & Technical IDs
    UUID getObjectId();
    LocalDateTime getDateAdded();
    String getResourceId();
    String getWorkstationId();

    // Relationship / Foreign Key
    UUID getUserId();
    String getUserName();// Derived from the VpiUsersCmp object
    // Time related fields
    LocalDateTime getStartTime();
    Integer getGmtOffset();
    LocalDateTime getGmtStartTime();
    Integer getDuration();

    // Trigger & Classification
    String getTriggeredByResourceTypeId();
    String getTriggeredByObjectId();
    String getFlagId();
    String getTags();
    String getSensitivityLevel();
    String getClientId();

    // Channel & Extension Info
    Integer getChannelNum();
    String getChannelName();
    String getExtensionNum();
    String getAgentId();
    String getPbxDnis();
    String getAnialidigits();
    String getDirection();

    // Media Management
    String getMediaFileId();
    String getMediaManagerId();
    String getMediaRetention();

    // Call Tracking
    String getCallId();
    String getPreviousCallId();
    String getGlobalCallId();

    // Platform & Service
    String getClassOfService();
    LocalDateTime getClassOfServiceDate();
    String getXPlatformRef();

    // Transcription & Audio Metadata
    String getTranscriptResult();
    String getWarehouseObjectKey();
    String getTranscriptStatus();
    Integer getAudioChannels();
    Boolean getHasTalkover();
}

