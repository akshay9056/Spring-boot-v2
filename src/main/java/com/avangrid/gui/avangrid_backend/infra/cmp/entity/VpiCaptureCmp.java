package com.avangrid.gui.avangrid_backend.infra.cmp.entity;

import com.avangrid.gui.avangrid_backend.model.entitiybase.VpiCaptureBase;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "vpvoiceobjects", schema = "vpicapturevoice")
@Immutable
public class VpiCaptureCmp extends VpiCaptureBase {

}
