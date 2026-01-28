package com.avangrid.gui.avangrid_backend.infra.rge.repository;

import com.avangrid.gui.avangrid_backend.infra.rge.entity.VpiCaptureRge;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VpiRgeRepo extends JpaRepository<VpiCaptureRge, UUID>, JpaSpecificationExecutor<VpiCaptureRge> {

    @EntityGraph(attributePaths = {"user"})
    Page<VpiCaptureRge> findAll(Specification<VpiCaptureRge> spec, Pageable pageable);

    List<VpiCaptureRge> findByObjectId(UUID objectId);
}

