package com.avangrid.gui.avangrid_backend.infra.cmp.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "vpiuserscmp")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VpiUsersCmp {
    @Id
    @Column(name = "userid", nullable = false)
    private UUID userid;

    @Column(name = "fullname")
    private String fullname;
}