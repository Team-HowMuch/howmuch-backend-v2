package com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "common_code_detail")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommonCodeJpaEntity extends BaseTimeJpaEntity {
    @EmbeddedId
    private CommonCodeJpaId id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(length = 100)
    private String attr1;

    @Column(length = 100)
    private String attr2;

    @Column(length = 100)
    private String attr3;

    @Column(name = "use_yn", nullable = false)
    private boolean active;
}
