package com.avangrid.gui.avangrid_backend.infra.rge.repository;

import com.avangrid.gui.avangrid_backend.infra.generic.VpiCaptureRepository;
import com.avangrid.gui.avangrid_backend.infra.rge.entity.VpiCaptureRge;
import org.springframework.stereotype.Repository;


@Repository
public interface VpiRgeRepo extends VpiCaptureRepository<VpiCaptureRge> {

}

