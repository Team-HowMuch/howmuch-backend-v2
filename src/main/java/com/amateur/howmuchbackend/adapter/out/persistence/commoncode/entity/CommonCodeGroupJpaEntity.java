package com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity;

import com.amateur.howmuchbackend.adapter.out.persistence.common.BaseTimeJpaEntity;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

@Entity
@Getter
@Table(name = "common_code_group")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommonCodeGroupJpaEntity extends BaseTimeJpaEntity implements Persistable<String> {
    @Id
    @Column(name = "group_code", length = 50, nullable = false)
    private String groupCode;

    @Column(name = "group_name", nullable = false, length = 100)
    private String groupName;

    @Column(length = 500)
    private String description;

    @Version
    @Column(nullable = false)
    private Long revision;

    @Column(name = "attr1_name", length = 50)
    private String attr1Name;

    @Column(name = "attr2_name", length = 50)
    private String attr2Name;

    @Column(name = "attr3_name", length = 50)
    private String attr3Name;

    @Column(name = "use_yn", nullable = false)
    private boolean active;

    @Transient
    private boolean newEntity = true;

    public CommonCodeGroupJpaEntity(CommonCodeGroup group) {
        this.groupCode = group.groupCode();
        updateFrom(group);
    }

    public void updateFrom(CommonCodeGroup group) {
        this.groupName = group.groupName();
        this.description = group.description();
        this.attr1Name = group.attr1Name();
        this.attr2Name = group.attr2Name();
        this.attr3Name = group.attr3Name();
        this.active = group.active();
    }

    @Override
    public String getId() {
        return groupCode;
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
