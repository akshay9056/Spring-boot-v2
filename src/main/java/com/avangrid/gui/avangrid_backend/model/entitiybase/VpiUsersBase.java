package com.avangrid.gui.avangrid_backend.model.entitiybase;


import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@MappedSuperclass
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VpiUsersBase implements Serializable {

    @Id
    @Column(name = "userID", nullable = false)
    private UUID userId;

    @Column(name = "fullName", nullable = false)
    private String fullName;
}
