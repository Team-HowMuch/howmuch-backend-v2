package com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity;

import com.amateur.howmuchbackend.domain.settlement.SettlementItemOption;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Getter
@Table(name = "settlement_item_options")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementItemOptionJpaEntity {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "settlement_id", nullable = false, updatable = false)
    private UUID settlementId;

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(nullable = false, updatable = false, length = SettlementItemOption.MAX_NAME_LENGTH)
    private String name;

    @Column(nullable = false, updatable = false)
    private long price;

    @Column(name = "display_order", nullable = false, updatable = false)
    private int displayOrder;

    public SettlementItemOptionJpaEntity(SettlementItemOption option) {
        id = option.id();
        settlementId = option.settlementId();
        itemId = option.itemId();
        name = option.name();
        price = option.price();
        displayOrder = option.displayOrder();
    }
}
