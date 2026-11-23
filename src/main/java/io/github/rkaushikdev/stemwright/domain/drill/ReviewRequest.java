package io.github.rkaushikdev.stemwright.domain.drill;

import io.github.rkaushikdev.stemwright.domain.ortho.HintLevel;
import io.github.rkaushikdev.stemwright.domain.ortho.ToleranceRequest;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One typed answer to one cell, as the drill hands it to {@link Review#answer}.
 *
 * @param cell the cell that was asked
 * @param given what the learner typed
 * @param on the study day of the answer, which decides the revision it is graded against
 * @param learnerId the learner who answered
 * @param sessionId the session the answer belongs to
 * @param sequence where the answer falls in its session, from one
 * @param deckKey the deck the cell was asked from
 * @param deckTolerance the tolerance level the deck runs at
 * @param cellTolerance a tolerance level fixed on the cell, which beats the deck
 * @param assignmentTolerance a tolerance level fixed on the assignment, which beats both
 * @param elapsedMs how long the learner took, in milliseconds, absent when it was not timed
 * @param hinted the furthest rung of the hint ladder the learner was shown before answering
 */
public record ReviewRequest(
        Cell cell,
        String given,
        LocalDate on,
        String learnerId,
        String sessionId,
        int sequence,
        String deckKey,
        Optional<String> deckTolerance,
        Optional<String> cellTolerance,
        Optional<String> assignmentTolerance,
        Optional<Double> elapsedMs,
        Optional<HintLevel> hinted) {

    public ReviewRequest {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(given, "given");
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(learnerId, "learnerId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(deckKey, "deckKey");
        Objects.requireNonNull(deckTolerance, "deckTolerance");
        Objects.requireNonNull(cellTolerance, "cellTolerance");
        Objects.requireNonNull(assignmentTolerance, "assignmentTolerance");
        Objects.requireNonNull(elapsedMs, "elapsedMs");
        Objects.requireNonNull(hinted, "hinted");
    }

    /** An empty builder; every part but the tolerance levels, the elapsed time and the hint must be set. */
    public static Builder builder() {
        return new Builder();
    }

    /** The tolerance levels the request names, for the orthography profile to resolve. */
    public ToleranceRequest tolerance() {
        return new ToleranceRequest(deckTolerance, cellTolerance, assignmentTolerance);
    }

    /**
     * Collects the parts of a request; the tolerance levels, the elapsed time and the hint may be
     * left out.
     */
    public static final class Builder {

        private Cell cell;
        private String given;
        private LocalDate on;
        private String learnerId;
        private String sessionId;
        private int sequence;
        private String deckKey;
        private String deckTolerance;
        private String cellTolerance;
        private String assignmentTolerance;
        private Double elapsedMs;
        private HintLevel hinted;

        private Builder() {}

        public Builder cell(Cell cell) {
            this.cell = cell;
            return this;
        }

        public Builder given(String given) {
            this.given = given;
            return this;
        }

        /** What the learner typed after being shown the hint at that rung. */
        public Builder given(String given, HintLevel hinted) {
            this.given = given;
            this.hinted = hinted;
            return this;
        }

        public Builder on(LocalDate on) {
            this.on = on;
            return this;
        }

        public Builder learnerId(String learnerId) {
            this.learnerId = learnerId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder sequence(int sequence) {
            this.sequence = sequence;
            return this;
        }

        public Builder deckKey(String deckKey) {
            this.deckKey = deckKey;
            return this;
        }

        public Builder deckTolerance(String level) {
            this.deckTolerance = level;
            return this;
        }

        public Builder cellTolerance(String level) {
            this.cellTolerance = level;
            return this;
        }

        public Builder assignmentTolerance(String level) {
            this.assignmentTolerance = level;
            return this;
        }

        public Builder elapsedMs(double elapsedMs) {
            this.elapsedMs = elapsedMs;
            return this;
        }

        /**
         * The request, once every required part is set.
         *
         * @throws NullPointerException naming the first required part that was never set
         */
        public ReviewRequest build() {
            return new ReviewRequest(
                    cell,
                    given,
                    on,
                    learnerId,
                    sessionId,
                    sequence,
                    deckKey,
                    Optional.ofNullable(deckTolerance),
                    Optional.ofNullable(cellTolerance),
                    Optional.ofNullable(assignmentTolerance),
                    Optional.ofNullable(elapsedMs),
                    Optional.ofNullable(hinted));
        }
    }
}
