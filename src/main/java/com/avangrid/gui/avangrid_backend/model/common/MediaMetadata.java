package com.avangrid.gui.avangrid_backend.model.common;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaMetadata {

    private String fileName;
    private String type;
    private String result;

    @Builder.Default
    private Map<String, String> fields = new HashMap<>();

    /**
     * Retrieves a field value safely.
     */
    public String getField(String fieldName) {
        return fields.getOrDefault(fieldName, "");
    }

    /**
     * Checks if a specific field exists.
     */
    public boolean hasField(String fieldName) {
        return fields.containsKey(fieldName);
    }

    /**
     * Validates if metadata has required core attributes.
     */
    public boolean isValid() {
        return fileName != null && !fileName.isEmpty() &&
                type != null && !type.isEmpty();
    }
}

