package com.amateur.howmuchbackend.adapter.out.persistence.external.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.external.entity.ConnectedInstitutionJpaEntity;
import com.amateur.howmuchbackend.application.account.model.BankConnectionSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConnectedInstitutionJpaRepository extends JpaRepository<ConnectedInstitutionJpaEntity, Long> {
    @Query("""
            select new com.amateur.howmuchbackend.application.account.model.BankConnectionSummary(
                institution.externalConnection.id, institution.organization,
                institution.status, institution.createdAt)
              from ConnectedInstitutionJpaEntity institution
             where institution.externalConnection.user.id = :userId
               and institution.externalConnection.active = true
               and institution.externalConnection.configurationFingerprint = :fingerprint
             order by institution.createdAt desc, institution.id desc
            """)
    List<BankConnectionSummary> findActiveSummaries(
            @Param("userId") Long userId, @Param("fingerprint") String fingerprint);

    boolean existsByExternalConnection_IdAndOrganization(Long connectionId, String organization);

    Optional<ConnectedInstitutionJpaEntity> findByExternalConnection_IdAndOrganization(Long connectionId, String organization);

    List<ConnectedInstitutionJpaEntity> findAllByExternalConnection_User_Id(Long userId);

    @Modifying
    @Query("delete from ConnectedInstitutionJpaEntity institution where institution.externalConnection.id = :connectionId")
    int deleteAllByConnectionId(@Param("connectionId") Long connectionId);
}
