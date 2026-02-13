package com.avangrid.gui.avangrid_backend.infra.nyseg.repository;

import com.avangrid.gui.avangrid_backend.infra.generic.VpiCaptureRepository;
import com.avangrid.gui.avangrid_backend.infra.nyseg.entity.VpiCaptureNyseg;
import org.springframework.stereotype.Repository;


@Repository
public interface VpiNysegRepo extends VpiCaptureRepository<VpiCaptureNyseg> {

}
