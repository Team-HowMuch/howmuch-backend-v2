package com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

@Entity
@Getter
@Table(name = "common_code_detail")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommonCodeJpaEntity extends BaseTimeJpaEntity implements Persistable<CommonCodeJpaId> {
    @EmbeddedId
    private CommonCodeJpaId id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(length = 255)
    private String attr1;

    @Column(length = 255)
    private String attr2;

    @Column(length = 255)
    private String attr3;

    @Column(name = "use_yn", nullable = false)
    private boolean active;

    @Transient
    private boolean newEntity = true;

    public CommonCodeJpaEntity(CommonCode commonCode) {
        this.id = new CommonCodeJpaId(commonCode.groupCode(), commonCode.code());
        updateFrom(commonCode);
    }

    public void updateFrom(CommonCode commonCode) {
        this.name = commonCode.name();
        this.description = commonCode.description();
        this.sortOrder = commonCode.sortOrder();
        this.attr1 = commonCode.attr1();
        this.attr2 = commonCode.attr2();
        this.attr3 = commonCode.attr3();
        this.active = commonCode.active();
    }

    public void deactivate() {
        this.active = false;
    }

    @Override
    public CommonCodeJpaId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    private void markNotNew() {
        newEntity = false;
    }
}
