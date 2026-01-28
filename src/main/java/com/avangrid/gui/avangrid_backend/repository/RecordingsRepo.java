package com.avangrid.gui.avangrid_backend.repository;

import com.avangrid.gui.avangrid_backend.model.Recording;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecordingsRepo extends JpaRepository<Recording, Long>, JpaSpecificationExecutor<Recording> {

    @Query("SELECT r FROM Recording r WHERE r.opco = :opco AND r.fileName = :fileName")
    List<Recording> findAllByOpcoAndFileName(@Param("opco") String opco,
                                             @Param("fileName") String fileName);
}