package com.amateur.howmuchbackend.adapter.out.persistence.user;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.user.Gender;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserJpaEntity extends BaseTimeJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "identity_key", nullable = false, length = 64, unique = true)
    private String identityKey;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String nickname;

    @Column(name = "public_id", nullable = false, length = 20, unique = true)
    private String publicId;

    @Column
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Gender gender;

    @Column(nullable = false, length = 20, unique = true)
    private String phoneNumber;

    @Column(nullable = false, length = 100)
    private String pinHash;

    @Column(length = 255)
    private String fcmToken;

    @Column(name = "avatar_file_name", length = 100)
    private String avatarFileName;

    @Column(nullable = false)
    private boolean phoneVerified;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    private boolean active;

    private LocalDateTime lastLoginAt;

    @Column(name = "required_terms_agreed_at")
    private LocalDateTime requiredTermsAgreedAt;

    @Column(name = "marketing_agreed_at")
    private LocalDateTime marketingAgreedAt;

    public UserJpaEntity(User user) {
        updateFrom(user);
    }

    public void updateFrom(User user) {
        this.identityKey = user.identityKey();
        this.name = user.name();
        this.nickname = user.nickname();
        this.publicId = user.publicId();
        this.birthDate = user.birthDate();
        this.gender = user.gender();
        this.phoneNumber = user.phoneNumber();
        this.pinHash = user.pinHash();
        this.fcmToken = user.fcmToken();
        this.avatarFileName = user.avatarFileName();
        this.phoneVerified = user.phoneVerified();
        this.role = user.role();
        this.active = user.active();
        this.lastLoginAt = user.lastLoginAt();
        this.requiredTermsAgreedAt = user.requiredTermsAgreedAt();
        this.marketingAgreedAt = user.marketingAgreedAt();
    }
}
