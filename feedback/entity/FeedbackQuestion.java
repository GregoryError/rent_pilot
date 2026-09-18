package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "feedback_questions")
@Getter @Setter @NoArgsConstructor
public class FeedbackQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "property_id")
    private Long propertyId;

    @Column(name = "question_text", nullable = false, length = 500)
    private String questionText;

    @Column(name = "question_type", nullable = false, length = 20)
    private String questionType = "SCALE"; // SCALE / TEXT

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean active = true;
}
