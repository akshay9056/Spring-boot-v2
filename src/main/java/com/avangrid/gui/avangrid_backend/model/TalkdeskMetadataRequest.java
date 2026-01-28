package com.avangrid.gui.avangrid_backend.model;

import lombok.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Data
@Setter
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TalkdeskMetadataRequest {
    
    @NotBlank(message = "Start date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", 
             message = "Start date must be in format yyyy-MM-dd HH:mm:ss")
    private String startDate;
    
    @NotBlank(message = "End date is required")
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}", 
             message = "End date must be in format yyyy-MM-dd HH:mm:ss")
    private String endDate;
    
    private String continuationToken;
    private Integer pageSize;
    private String interactionId;
    private String customerPhoneNumber;
    private String talkdeskPhoneNumber;
    private String callType;
}