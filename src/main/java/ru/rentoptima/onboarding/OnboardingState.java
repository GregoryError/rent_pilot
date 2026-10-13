package ru.rentoptima.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;

/**
 * Состояние онбординга одного пользователя: мастер, тур, подсказки, чек-лист.
 * Ряда нет — пользователь создан до появления онбординга и не получает его,
 * пока сам не попросит.
 */
@Entity
@Table(name = "onboarding_state")
@Getter
@Setter
@NoArgsConstructor
public class OnboardingState {

    public static final String BRANCH_PLATFORMS = "A";
    public static final String BRANCH_DIRECT = "B";
    public static final String BRANCH_BOTH = "AB";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(length = 2)
    private String branch;

    @Column(name = "wizard_step", nullable = false, length = 20)
    private String wizardStep = "start";

    @Column(name = "wizard_status", nullable = false, length = 10)
    private String wizardStatus = STATUS_ACTIVE;

    /** Выбранные площадки, созданные объект и страница; позже — тур, подсказки, чек-лист. */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private JsonNode data = JsonNodeFactory.instance.objectNode();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "wizard_finished_at")
    private LocalDateTime wizardFinishedAt;

    public boolean wizardActive() {
        return STATUS_ACTIVE.equals(wizardStatus);
    }

    public boolean wantsPlatforms() {
        return BRANCH_PLATFORMS.equals(branch) || BRANCH_BOTH.equals(branch);
    }

    public boolean wantsPage() {
        return BRANCH_DIRECT.equals(branch) || BRANCH_BOTH.equals(branch);
    }

    /** Изменяемая копия данных: после правки её нужно положить обратно через {@link #setData}. */
    public ObjectNode dataCopy() {
        return data != null && data.isObject() ? ((ObjectNode) data).deepCopy() : JsonNodeFactory.instance.objectNode();
    }

    public Long dataLong(String key) {
        JsonNode n = data == null ? null : data.get(key);
        return n == null || !n.isNumber() ? null : n.asLong();
    }
}
