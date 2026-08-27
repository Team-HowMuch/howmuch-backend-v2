package com.amateur.howmuchbackend.adapter.out.persistence.external.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.external.entity.ConnectedInstitutionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectedInstitutionJpaRepository extends JpaRepository<ConnectedInstitutionJpaEntity, Long> {
    boolean existsByExternalConnection_IdAndOrganization(Long connectionId, String organization);

    @Modifying
    @Query("delete from ConnectedInstitutionJpaEntity institution where institution.externalConnection.id = :connectionId")
    int deleteAllByConnectionId(@Param("connectionId") Long connectionId);
}
