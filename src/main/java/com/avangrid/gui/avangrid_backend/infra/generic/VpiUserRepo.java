package com.avangrid.gui.avangrid_backend.infra.generic;


import com.avangrid.gui.avangrid_backend.model.entitiybase.VpiUsersBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Generic repository for VPI Users entities across multiple databases
 * @param <U> User entity type extending VpiUsersBase
 */
@NoRepositoryBean
public interface VpiUserRepo<U extends VpiUsersBase>
        extends JpaRepository<U, UUID> {

    /**
     * Batch fetch users by IDs
     * Used for enriching capture records
     */
    List<U> findByUserIdIn(Collection<UUID> userIds);

    @Query(
            value = """
            select distinct u.userid
            from :#{#entityName.replace('VpiUsers', 'vpi')}.vpusers u
            where lower(u.fullname) like any (
                select concat('%', lower(n), '%')
                from unnest(cast(:names as text[])) as n
            )
        """,
            nativeQuery = true
    )
    List<UUID> findUserIdsByFullNameContainsAny(@Param("names") String[] names);
}