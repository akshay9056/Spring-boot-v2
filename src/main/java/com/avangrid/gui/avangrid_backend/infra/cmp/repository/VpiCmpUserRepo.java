package com.avangrid.gui.avangrid_backend.infra.cmp.repository;



import com.avangrid.gui.avangrid_backend.infra.cmp.entity.VpiUsersCmp;
import com.avangrid.gui.avangrid_backend.infra.generic.VpiUserRepo;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;


import java.util.List;
import java.util.UUID;

@Repository
public interface VpiCmpUserRepo extends VpiUserRepo<VpiUsersCmp> {

    @Override
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

