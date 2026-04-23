package com.avangrid.gui.avangrid_backend.infra.generic;

import com.avangrid.gui.avangrid_backend.model.entitiybase.VpiCaptureBase;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.*;

/**
 * Generic repository for VPI Capture entities across multiple databases
 * @param <T> Entity type extending VpiCaptureBase
 */
@NoRepositoryBean
public interface VpiCaptureRepository<T extends VpiCaptureBase>
        extends JpaRepository<T, UUID>, JpaSpecificationExecutor<T> {

    /**
     * Find all captures with specification and pagination
     */
    Page<T> findAll(Specification<T> spec, Pageable pageable);

    /**
     * Find captures by object ID
     */
    List<T> findByObjectId(UUID objectId);
}
