package com.avangrid.gui.avangrid_backend.infra.cmp.repository;

import com.avangrid.gui.avangrid_backend.infra.cmp.entity.VpiCaptureCmp;
import com.avangrid.gui.avangrid_backend.infra.generic.VpiCaptureRepository;
import org.springframework.stereotype.Repository;


@Repository
public interface VpiCmpRepo extends VpiCaptureRepository<VpiCaptureCmp> {

}
