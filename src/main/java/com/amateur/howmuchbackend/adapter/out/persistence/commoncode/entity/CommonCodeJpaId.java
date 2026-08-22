package com.amateur.howmuchbackend.adapter.out.persistence.commoncode.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;

@Embeddable
@Getter
@EqualsAndHashCode
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommonCodeJpaId implements Serializable {
    @Column(name = "group_code", length = 50, nullable = false)
    private String groupCode;

    @Column(name = "code", length = 50, nullable = false)
    private String code;

}
