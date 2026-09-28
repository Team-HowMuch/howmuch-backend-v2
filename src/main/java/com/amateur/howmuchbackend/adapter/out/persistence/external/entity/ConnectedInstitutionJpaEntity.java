package com.amateur.howmuchbackend.adapter.out.persistence.external.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
        name = "connected_institutions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_connected_institution_connection_organization",
                columnNames = {"external_connection_id", "organization"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConnectedInstitutionJpaEntity extends BaseTimeJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "external_connection_id", nullable = false)
    private ExternalConnectionJpaEntity externalConnection;

    @Column(nullable = false, length = 10)
    private String organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExternalConnectionStatus status;

    @Column(name = "last_result_code", length = 30)
    private String lastResultCode;

    @Column(name = "account_credentials_local_ciphertext", columnDefinition = "text")
    private String localCredentialsCiphertext;

    @Column(name = "account_credentials_kms_ciphertext", columnDefinition = "text")
    private String kmsCredentialsCiphertext;

    public ConnectedInstitutionJpaEntity(
            ExternalConnectionJpaEntity externalConnection,
            String organization,
            ExternalConnectionStatus status,
            String lastResultCode,
            String localCredentialsCiphertext,
            String kmsCredentialsCiphertext
    ) {
        this.externalConnection = externalConnection;
        this.organization = organization;
        this.status = status;
        this.lastResultCode = lastResultCode;
        this.localCredentialsCiphertext = localCredentialsCiphertext;
        this.kmsCredentialsCiphertext = kmsCredentialsCiphertext;
    }

    public void updateCredentials(String localCiphertext, String kmsCiphertext) {
        this.localCredentialsCiphertext = localCiphertext;
        this.kmsCredentialsCiphertext = kmsCiphertext;
    }

    public void saveKmsCredentialsIfAbsent(String kmsCiphertext) {
        if (this.kmsCredentialsCiphertext == null) {
            this.kmsCredentialsCiphertext = kmsCiphertext;
        }
    }

    public void clearCredentials() {
        this.localCredentialsCiphertext = null;
        this.kmsCredentialsCiphertext = null;
    }
}
