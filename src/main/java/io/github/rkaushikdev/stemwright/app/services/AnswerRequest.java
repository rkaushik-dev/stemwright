package io.github.rkaushikdev.stemwright.app.services;

import io.github.rkaushikdev.stemwright.domain.ortho.HintLevel;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One typed answer, as a learner sends it to {@link DrillService#answer}.
 *
 * @param learnerId the learner answering
 * @param language the language key of the deck
 * @param deckKey the deck the cell was asked from
 * @param cellId the cell answered
 * @param given what the learner typed
 * @param on the study day of the answer, the clock's day when absent
 * @param elapsedMs how long the learner took, in milliseconds, absent when it was not timed
 * @param hinted the furthest rung of the hint ladder the learner was shown before answering,
 *     absent when they answered unaided
 */
public record AnswerRequest(
        String learnerId,
        String language,
        String deckKey,
        String cellId,
        String given,
        Optional<LocalDate> on,
        Optional<Double> elapsedMs,
        Optional<HintLevel> hinted) {

    public AnswerRequest {
        Objects.requireNonNull(learnerId, "learnerId");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(deckKey, "deckKey");
        Objects.requireNonNull(cellId, "cellId");
        Objects.requireNonNull(given, "given");
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(elapsedMs, "elapsedMs");
        Objects.requireNonNull(hinted, "hinted");
    }

    /** An untimed, unaided answer given today. */
    public static AnswerRequest of(String learnerId, String language, String deckKey, String cellId, String given) {
        return new AnswerRequest(
                learnerId, language, deckKey, cellId, given, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** An untimed answer given today after the learner was shown the hint at that rung. */
    public static AnswerRequest of(
            String learnerId, String language, String deckKey, String cellId, String given, HintLevel hinted) {
        return new AnswerRequest(
                learnerId, language, deckKey, cellId, given, Optional.empty(), Optional.empty(), Optional.of(hinted));
    }

    /** A copy given on the study day. */
    public AnswerRequest withOn(LocalDate day) {
        return new AnswerRequest(learnerId, language, deckKey, cellId, given, Optional.of(day), elapsedMs, hinted);
    }

    /** A copy timed at the milliseconds. */
    public AnswerRequest withElapsedMs(double milliseconds) {
        return new AnswerRequest(learnerId, language, deckKey, cellId, given, on, Optional.of(milliseconds), hinted);
    }
}
