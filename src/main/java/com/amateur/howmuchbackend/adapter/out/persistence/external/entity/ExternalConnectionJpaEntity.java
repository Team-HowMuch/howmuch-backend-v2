package com.amateur.howmuchbackend.adapter.out.persistence.external.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.user.UserJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "external_connections")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExternalConnectionJpaEntity extends BaseTimeJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserJpaEntity user;

    @Column(name = "connected_id_ciphertext", nullable = false, length = 1024)
    private String connectedIdCiphertext;

    @Column(name = "configuration_fingerprint", nullable = false, length = 64)
    private String configurationFingerprint;

    @Column(nullable = false)
    private boolean active;

    public ExternalConnectionJpaEntity(
            UserJpaEntity user,
            String connectedIdCiphertext,
            String configurationFingerprint
    ) {
        this.user = user;
        this.connectedIdCiphertext = connectedIdCiphertext;
        this.configurationFingerprint = configurationFingerprint;
        this.active = true;
    }

    public void reactivate(String connectedIdCiphertext, String configurationFingerprint) {
        this.connectedIdCiphertext = connectedIdCiphertext;
        this.configurationFingerprint = configurationFingerprint;
        this.active = true;
    }
}
