package com.amateur.howmuchbackend.adapter.out.persistence.recap.mapper;

import com.amateur.howmuchbackend.application.recap.model.RecapAllocationFacts;
import com.amateur.howmuchbackend.domain.settlement.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RecapAllocationFactsMapper {
    private final ObjectMapper json;

    public RecapAllocationFacts map(UUID settlementId, UUID ownParticipant, boolean submitted,
                                    int submissionCount, long total, String itemsJson, String peopleJson,
                                    String selectionsJson, long discount, long fee) {
        List<SettlementItem> items = new ArrayList<>();
        for (var row : json.readTree(itemsJson)) {
            items.add(new SettlementItem(id(row, "id"), settlementId, id(row, "expense_id"),
                    text(row, "name"), row.path("quantity").intValue(), row.path("amount").longValue(),
                    row.path("display_order").intValue(), time(row, "created_at"), time(row, "updated_at"),
                    row.path("discount").longValue(), List.of()));
        }
        List<SettlementParticipant> people = new ArrayList<>();
        for (var row : json.readTree(peopleJson)) {
            people.add(new SettlementParticipant(id(row, "id"), settlementId,
                    SettlementParticipantSource.valueOf(text(row, "source")), number(row, "linked_user_id"),
                    text(row, "phone_hash"), number(row, "source_group_id"), text(row, "display_name"),
                    row.path("display_order").intValue(), time(row, "created_at"), time(row, "updated_at")));
        }
        List<SettlementItemSelection> selections = new ArrayList<>();
        for (var row : json.readTree(selectionsJson)) {
            selections.add(new SettlementItemSelection(id(row, "id"), settlementId, id(row, "item_id"),
                    id(row, "participant_id"), SettlementItemAllocationFraction.valueOf(text(row, "fraction")),
                    row.path("units").intValue(), time(row, "created_at"), time(row, "updated_at")));
        }
        return new RecapAllocationFacts(ownParticipant, submitted, submissionCount, total, discount, fee,
                List.copyOf(people), List.copyOf(items), List.copyOf(selections));
    }

    private String text(JsonNode row, String name) { return row.path(name).isNull() ? null : row.path(name).stringValue(); }
    private Long number(JsonNode row, String name) { return row.path(name).isNull() ? null : row.path(name).longValue(); }
    private UUID id(JsonNode row, String name) { return UUID.fromString(text(row, name)); }
    private LocalDateTime time(JsonNode row, String name) { return LocalDateTime.parse(text(row, name)); }
}
