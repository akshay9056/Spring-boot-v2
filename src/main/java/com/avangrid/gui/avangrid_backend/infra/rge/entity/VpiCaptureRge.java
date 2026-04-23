package com.avangrid.gui.avangrid_backend.infra.rge.entity;

import com.avangrid.gui.avangrid_backend.model.entitiybase.VpiCaptureBase;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "vpvoiceobjects", schema = "vpicapturevoice")
@Immutable
public class VpiCaptureRge extends VpiCaptureBase {

}