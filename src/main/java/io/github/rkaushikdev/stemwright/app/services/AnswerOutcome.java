package io.github.rkaushikdev.stemwright.app.services;

import io.github.rkaushikdev.stemwright.domain.drill.ReviewResult;
import io.github.rkaushikdev.stemwright.domain.drill.Session;
import io.github.rkaushikdev.stemwright.domain.ortho.Hint;
import java.util.Objects;
import java.util.Optional;

/**
 * What answering did, once it was written.
 *
 * @param review how the answer was marked and everything it changed
 * @param session the open session with the answer recorded in it
 * @param hint the rung of the hint ladder a wrong answer earned, nothing for a right one
 */
public record AnswerOutcome(ReviewResult review, Session session, Optional<Hint> hint) {

    public AnswerOutcome {
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(hint, "hint");
    }
}
