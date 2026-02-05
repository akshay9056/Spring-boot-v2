package com.avangrid.gui.avangrid_backend.infra.nyseg.repository;

import com.avangrid.gui.avangrid_backend.infra.nyseg.entity.VpiCaptureNyseg;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VpiNysegRepo extends JpaRepository<VpiCaptureNyseg, UUID>, JpaSpecificationExecutor<VpiCaptureNyseg> {


    Page<VpiCaptureNyseg> findAll(Specification<VpiCaptureNyseg> spec, Pageable pageable);

    List<VpiCaptureNyseg> findByObjectId(UUID objectId);
}
