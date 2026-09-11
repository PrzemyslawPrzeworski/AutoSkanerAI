package com.example.autoskaner_ai.saved;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One analysis a user chose to keep (FR-010 … FR-012).
 *
 * <p>The analysis itself is stored as the serialised {@code AnalysisResponse} in {@link #payload},
 * with the fields the list view needs copied out into columns beside it. {@code title} and
 * {@code note} are the user's own — everything else is a record of what the model and the registry
 * said at {@link #createdAt} and is never rewritten, because a saved verdict that can be edited is
 * no longer evidence of anything.
 *
 * <p>The owner is a plain {@code userId} rather than a {@code @ManyToOne}: nothing here traverses to
 * the account, the foreign key is enforced in the schema, and a lazy association behind
 * {@code spring.jpa.open-in-view=false} costs more than it buys.
 */
@Entity
@Table(name = "analyses")
public class SavedAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String note;

    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Column(length = 100)
    private String make;

    @Column(length = 100)
    private String model;

    @Column(name = "production_year")
    private Integer productionYear;

    @Column(name = "price_amount", precision = 12, scale = 2)
    private BigDecimal priceAmount;

    @Column(name = "price_currency", length = 8)
    private String priceCurrency;

    @Column(name = "mileage_km")
    private Integer mileageKm;

    @Column(name = "verdict_code", length = 40)
    private String verdictCode;

    @Column(name = "overall_score")
    private Integer overallScore;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SavedAnalysis() {
        // for JPA
    }

    /**
     * The columns without which the row means nothing. The summary columns arrive separately through
     * {@link #describe} because every one of them is legitimately absent when extraction found no
     * make, no price or no mileage.
     */
    public static SavedAnalysis create(Long userId, String title, String payload, Instant now) {
        SavedAnalysis saved = new SavedAnalysis();
        saved.userId = userId;
        saved.title = requireTitle(title);
        saved.payload = payload;
        saved.createdAt = now;
        saved.updatedAt = now;
        return saved;
    }

    /** The denormalised summary the list view reads instead of parsing {@link #payload}. */
    public record Summary(
            String make,
            String model,
            Integer productionYear,
            BigDecimal priceAmount,
            String priceCurrency,
            Integer mileageKm,
            String verdictCode,
            Integer overallScore) {}

    public SavedAnalysis describe(Summary summary) {
        this.make = summary.make();
        this.model = summary.model();
        this.productionYear = summary.productionYear();
        this.priceAmount = summary.priceAmount();
        this.priceCurrency = summary.priceCurrency();
        this.mileageKm = summary.mileageKm();
        this.verdictCode = summary.verdictCode();
        this.overallScore = summary.overallScore();
        return this;
    }

    public SavedAnalysis from(String sourceUrl) {
        this.sourceUrl = sourceUrl;
        return this;
    }

    /**
     * The update operation: a user renames a saved analysis or annotates it. Nothing else about the
     * row is mutable.
     */
    public void edit(String title, String note, Instant now) {
        this.title = requireTitle(title);
        this.note = note;
        this.updatedAt = now;
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        return title;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public String getNote() {
        return note;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getMake() {
        return make;
    }

    public String getModel() {
        return model;
    }

    public Integer getProductionYear() {
        return productionYear;
    }

    public BigDecimal getPriceAmount() {
        return priceAmount;
    }

    public String getPriceCurrency() {
        return priceCurrency;
    }

    public Integer getMileageKm() {
        return mileageKm;
    }

    public String getVerdictCode() {
        return verdictCode;
    }

    public Integer getOverallScore() {
        return overallScore;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
