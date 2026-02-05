package com.avangrid.gui.avangrid_backend.infra.cmp.repository;



import com.avangrid.gui.avangrid_backend.infra.cmp.entity.VpiUsersCmp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface VpiCmpUserRepo extends JpaRepository<VpiUsersCmp, UUID> {

    /**
     * Batch fetch users by IDs
     * Used for enriching capture records
     */
    List<VpiUsersCmp> findByUserIdIn(Collection<UUID> userIds);

    /**
     * Optional: search users by fullname (case-insensitive)
     * Used BEFORE capture query if filtering by name
     */
    @Query(
            value = """
        select distinct u.userid
        from vpicore.vpusers u
        where lower(u.fullname) like any (
            select concat('%', lower(n), '%')
            from unnest(cast(:names as text[])) as n
        )
    """,
            nativeQuery = true
    )
    List<UUID> findUserIdsByFullNameContainsAny(
            @Param("names") String[] names
    );
}

