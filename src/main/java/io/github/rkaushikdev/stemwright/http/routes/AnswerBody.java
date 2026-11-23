package io.github.rkaushikdev.stemwright.http.routes;

import io.github.rkaushikdev.stemwright.app.services.AnswerOutcome;
import io.github.rkaushikdev.stemwright.domain.drill.ReviewResult;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.ortho.HintLevel;
import io.github.rkaushikdev.stemwright.infra.Codec;
import io.github.rkaushikdev.stemwright.infra.json.Json;
import io.github.rkaushikdev.stemwright.infra.json.JsonObject;
import java.util.Optional;

/**
 * What one marked answer looks like on the wire, written once for the single answer route and for
 * every accepted row of a batch. A client that went offline is owed the same miss, variants and
 * hint for each answer it sends later as it would have had answering them one at a time.
 */
final class AnswerBody {

    private AnswerBody() {}

    /**
     * The {@code hinted} field of an answer, the rung of the hint ladder the learner saw first, or
     * nothing when it is left out.
     *
     * @throws ValidationException on {@code <path>.hinted} for a value that names no rung
     */
    static Optional<HintLevel> hintedFrom(JsonObject row, String path) {
        return Codec.readOptionalString(row, path, "hinted").map(key -> HintLevel.fromKey(key)
                .orElseThrow(() -> ValidationException.field(path + ".hinted", "is not a hint level: " + key)));
    }

    /**
     * Puts the answer's fields after whatever the builder already holds, in their fixed order: the
     * verdict, the expected form, the miss or null, how many twins were credited, the session and
     * the sequence, the variant forms and the hint or null.
     */
    static JsonObject.Builder into(JsonObject.Builder out, AnswerOutcome outcome) {
        ReviewResult review = outcome.review();
        return out.put("verdict", review.match().verdict().key())
                .put("expected", review.expected())
                .put(
                        "miss",
                        review.match()
                                .miss()
                                .<Json>map(miss -> Json.of(miss.key()))
                                .orElse(Json.NULL))
                .put("twinsCredited", review.twinsCredited())
                .put("sessionId", outcome.session().id())
                .put("sequence", review.attempt().sequence())
                .put(
                        "variants",
                        Json.array(
                                review.variants().stream().<Json>map(Json::of).toList()))
                .put(
                        "hint",
                        outcome.hint()
                                .<Json>map(hint -> Json.object()
                                        .put("level", hint.level().key())
                                        .put("text", hint.text())
                                        .build())
                                .orElse(Json.NULL));
    }
}
