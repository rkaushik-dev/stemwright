package io.github.rkaushikdev.stemwright.http.routes;

import io.github.rkaushikdev.stemwright.domain.ortho.HintLevel;
import java.util.Objects;
import java.util.Optional;

/**
 * One answer of a batch, as {@link BulkRoutes#readBatch} reads it off a request body.
 *
 * @param cellId the cell answered
 * @param given what the learner typed
 * @param hinted the rung of the hint ladder the learner saw before answering, absent when unaided
 */
public record BulkAnswer(String cellId, String given, Optional<HintLevel> hinted) {

    public BulkAnswer {
        Objects.requireNonNull(cellId, "cellId");
        Objects.requireNonNull(given, "given");
        Objects.requireNonNull(hinted, "hinted");
    }
}
