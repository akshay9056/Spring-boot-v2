package com.avangrid.gui.avangrid_backend.model;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "recordings")
public class Recording {

    @Id
    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "extension_num")
    private String extensionNum;

    @Column(name = "object_id", nullable = false)
    private String objectId;

    @Column(name = "channel_num")
    private String channelNum;

    @Column(name = "ani_ali_digits")
    private String aniAliDigits;

    @Column(name = "name")
    private String name;

    @Column(name = "date_added", nullable = false)
    private LocalDateTime dateAdded;

    @Column(name = "opco")
    private String opco;

    @Column(name = "direction")
    private boolean direction;

    @Column(name = "duration")
    private int duration;

    @Column(name = "agent_id")
    private String agentID;
    // Getters and setters
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getExtensionNum() { return extensionNum; }
    public void setExtensionNum(String extensionNum) { this.extensionNum = extensionNum; }

    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }

    public String getChannelNum() { return channelNum; }
    public void setChannelNum(String channelNum) { this.channelNum = channelNum; }

    public String getAniAliDigits() { return aniAliDigits; }
    public void setAniAliDigits(String aniAliDigits) { this.aniAliDigits = aniAliDigits; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDateTime getDateAdded() { return dateAdded; }
    public void setDateAdded(LocalDateTime dateAdded) { this.dateAdded = dateAdded; }

    public String getOpco() {return opco;}
    public void setOpco(String opco) { this.opco = opco; }

    public boolean getDirection() {return direction;}
    public void setDirection(boolean direction) { this.direction = direction; }

    public int getDuration() {return duration;}
    public void setDuration(int duration) { this.duration = duration; }

    public String getAgentID() {return agentID;}
    public void setAgentID(String agentID) { this.agentID = agentID; }

}
