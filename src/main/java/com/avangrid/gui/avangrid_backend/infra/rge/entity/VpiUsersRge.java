package com.avangrid.gui.avangrid_backend.infra.rge.entity;

import com.avangrid.gui.avangrid_backend.model.entitiybase.VpiUsersBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;


@Entity
@Table(name = "vpUsers", schema = "vpicore")
@Immutable
public class VpiUsersRge extends VpiUsersBase {

}
