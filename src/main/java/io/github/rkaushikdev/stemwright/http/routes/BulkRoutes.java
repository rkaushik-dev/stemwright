package io.github.rkaushikdev.stemwright.http.routes;

import io.github.rkaushikdev.stemwright.app.services.AnswerOutcome;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.http.Auth;
import io.github.rkaushikdev.stemwright.http.AuthedRequest;
import io.github.rkaushikdev.stemwright.http.HttpResponse;
import io.github.rkaushikdev.stemwright.http.Method;
import io.github.rkaushikdev.stemwright.http.Query;
import io.github.rkaushikdev.stemwright.http.Route;
import io.github.rkaushikdev.stemwright.infra.Codec;
import io.github.rkaushikdev.stemwright.infra.json.Json;
import io.github.rkaushikdev.stemwright.infra.json.JsonObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Answering several cells in one request, for a client that went offline.
 *
 * <p>The answers are applied in order and the run does not stop at the first failure, because a
 * batch from a phone that lost signal is exactly the case where one bad row should not lose the
 * other nineteen. Every answer gets a result, and the response says how many of each there were so
 * a client can decide whether to retry.
 *
 * <p>The batch is capped. An uncapped bulk endpoint is a way to hold a request open for a minute,
 * and there is no client that needs more than a sitting.
 */
public final class BulkRoutes {

    /** The most answers one batch may hold. */
    public static final int MAX_BATCH = 100;

    private static final int ALL_LANDED = 200;
    private static final int SOME_REFUSED = 207;

    private BulkRoutes() {}

    /**
     * Reads the {@code answers} of a body, in order.
     *
     * @throws ValidationException for a body that is not an object, {@code answers} that is not an
     *     array, holds no answer or more than {@link #MAX_BATCH}, and for the first row that is not an
     *     object with a string {@code cellId} and {@code given} and, when it has one, a {@code
     *     hinted} that names a rung of the hint ladder, named by its index
     */
    public static List<BulkAnswer> readBatch(Json body) {
        JsonObject row = Codec.requireObject("body", body);
        List<Json> rows = Codec.readArray(row, "body", "answers");
        if (rows.isEmpty()) {
            throw ValidationException.field("body.answers", "must hold at least one answer");
        }
        if (rows.size() > MAX_BATCH) {
            throw ValidationException.field("body.answers", "must hold at most " + MAX_BATCH + " answers");
        }
        List<BulkAnswer> answers = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index += 1) {
            String path = "body.answers." + index;
            JsonObject answer = Codec.requireObject(path, rows.get(index));
            answers.add(new BulkAnswer(
                    Codec.readString(answer, path, "cellId"),
                    Codec.readString(answer, path, "given"),
                    AnswerBody.hintedFrom(answer, path)));
        }
        return List.copyOf(answers);
    }

    /** {@code POST .../answers/bulk}, behind {@link Auth#withActor}. */
    public static List<Route> routes(DrillRouteDeps deps) {
        return List.of(Route.of(
                Method.POST,
                "/learners/:learnerId/languages/:language/decks/:deckKey/answers/bulk",
                Auth.withActor(deps.directory(), request -> answerAll(deps, request))));
    }

    /**
     * Answers the batch in order. An accepted answer's result carries every field the single answer
     * route writes; a refused answer, whatever refused it, becomes a result carrying its message, and
     * the response is a 207 rather than a 200 when there was one.
     */
    private static HttpResponse answerAll(DrillRouteDeps deps, AuthedRequest request) {
        DrillRoutes.requireDrill(deps, request);

        // A request with no body is refused exactly as a body of null is: neither is an object.
        List<BulkAnswer> batch = readBatch(request.body().orElse(Json.NULL));
        Optional<LocalDate> day = Query.dayFrom(request.query());
        List<Json> results = new ArrayList<>(batch.size());
        int accepted = 0;
        int refused = 0;

        for (BulkAnswer answer : batch) {
            try {
                AnswerOutcome outcome = deps.drill()
                        .answer(DrillRoutes.answerFor(request, day, answer.cellId(), answer.given(), answer.hinted()));
                accepted += 1;
                results.add(AnswerBody.into(
                                Json.object().put("cellId", answer.cellId()).put("ok", true), outcome)
                        .build());
            } catch (RuntimeException error) {
                refused += 1;
                results.add(Json.object()
                        .put("cellId", answer.cellId())
                        .put("ok", false)
                        .put("message", messageOf(error))
                        .build());
            }
        }

        return HttpResponse.json(
                refused == 0 ? ALL_LANDED : SOME_REFUSED,
                Json.object()
                        .put("accepted", accepted)
                        .put("refused", refused)
                        .put("results", Json.array(results))
                        .build());
    }

    /** The failure's message, or the failure itself as text when it carries none. */
    private static String messageOf(RuntimeException error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
