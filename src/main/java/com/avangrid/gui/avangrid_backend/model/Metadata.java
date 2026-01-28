package com.avangrid.gui.avangrid_backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "metadata")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Metadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileName;
    private LocalDate dateAdded;
    private String extensionNum;
    private String objectID;
    private String channelNum;
    private String aniAliDigits;
    private String name;
}
