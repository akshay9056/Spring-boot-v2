package com.avangrid.gui.avangrid_backend.infra.cmp.repository;

import com.avangrid.gui.avangrid_backend.infra.cmp.entity.VpiCaptureCmp;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.*;

@Repository
public interface VpiCmpRepo extends JpaRepository<VpiCaptureCmp, UUID>, JpaSpecificationExecutor<VpiCaptureCmp> {

    @EntityGraph(attributePaths = {"user"})
    Page<VpiCaptureCmp> findAll( Specification<VpiCaptureCmp> spec, Pageable pageable);

    List<VpiCaptureCmp> findByObjectId(UUID objectId);
}
