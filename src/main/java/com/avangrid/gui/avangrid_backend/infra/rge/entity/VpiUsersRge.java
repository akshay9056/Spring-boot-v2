package com.avangrid.gui.avangrid_backend.infra.rge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "vpUsers", schema = "vpicore")
@Getter
@Setter
@NoArgsConstructor
@Immutable
public class VpiUsersRge implements Serializable {

    @Id
    @Column(name = "userID", nullable = false)
    private UUID userId;

    @Column(name = "fullName", nullable = false)
    private String fullName;
}
