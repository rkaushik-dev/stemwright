package io.github.rkaushikdev.stemwright.http.routes;

import io.github.rkaushikdev.stemwright.app.services.AnswerOutcome;
import io.github.rkaushikdev.stemwright.app.services.AnswerRequest;
import io.github.rkaushikdev.stemwright.domain.course.CourseScope;
import io.github.rkaushikdev.stemwright.domain.course.Permission;
import io.github.rkaushikdev.stemwright.domain.drill.Queue;
import io.github.rkaushikdev.stemwright.domain.drill.SessionSummary;
import io.github.rkaushikdev.stemwright.domain.ortho.HintLevel;
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
import java.util.List;
import java.util.Optional;

/**
 * The drill routes. There are three, and between them they are the whole learner facing api: what
 * should I answer, here is my answer, I am done.
 *
 * <p>Every one of them takes the day from the query string when it is given, which looks like a
 * testing affordance and is not. A learner in Auckland is on tomorrow's date before a learner in
 * Lisbon has had breakfast, and the client knows which civil day the learner believes they are on.
 *
 * <p>Each route needs the {@code course} query parameter, checks that the caller may drill on that
 * course, and only then reads anything else.
 */
public final class DrillRoutes {

    private DrillRoutes() {}

    /**
     * {@code GET .../queue}, {@code POST .../answers} and {@code POST .../sessions/:sessionId/finish},
     * each behind {@link Auth#withActor}.
     */
    public static List<Route> routes(DrillRouteDeps deps) {
        return List.of(
                Route.of(
                        Method.GET,
                        "/learners/:learnerId/languages/:language/decks/:deckKey/queue",
                        Auth.withActor(deps.directory(), request -> queue(deps, request))),
                Route.of(
                        Method.POST,
                        "/learners/:learnerId/languages/:language/decks/:deckKey/answers",
                        Auth.withActor(deps.directory(), request -> answer(deps, request))),
                Route.of(
                        Method.POST,
                        "/learners/:learnerId/sessions/:sessionId/finish",
                        Auth.withActor(deps.directory(), request -> finish(deps, request))));
    }

    /**
     * Refuses a request whose query names no course, with a validation failure, and a caller who may
     * not drill on the course it names, with a forbidden one.
     */
    static void requireDrill(DrillRouteDeps deps, AuthedRequest request) {
        CourseScope scope = deps.scopeOf().apply(Query.requireCourseKey(request.query()));
        request.actor().requirePermission(Permission.DRILL, scope);
    }

    /**
     * The answer to the request's deck, given after the hint at that rung when one is named, on the
     * query's day when it names one and on the service's day otherwise.
     */
    static AnswerRequest answerFor(
            AuthedRequest request, Optional<LocalDate> day, String cellId, String given, Optional<HintLevel> hinted) {
        String learnerId = request.params().get("learnerId");
        String language = request.params().get("language");
        String deckKey = request.params().get("deckKey");
        AnswerRequest asked = hinted.map(level -> AnswerRequest.of(learnerId, language, deckKey, cellId, given, level))
                .orElseGet(() -> AnswerRequest.of(learnerId, language, deckKey, cellId, given));
        return day.map(asked::withOn).orElse(asked);
    }

    private static HttpResponse queue(DrillRouteDeps deps, AuthedRequest request) {
        String learnerId = request.params().get("learnerId");
        requireDrill(deps, request);

        String language = request.params().get("language");
        String deckKey = request.params().get("deckKey");
        Optional<LocalDate> day = Query.dayFrom(request.query());
        Queue built = day.map(on -> deps.drill().queue(learnerId, language, deckKey, on))
                .orElseGet(() -> deps.drill().queue(learnerId, language, deckKey));

        return HttpResponse.json(
                200,
                Json.object()
                        .put("on", built.on().toString())
                        .put("deckKey", built.deckKey())
                        .put(
                                "cells",
                                Json.array(built.cells().stream()
                                        .map(cell -> Json.object()
                                                .put("id", cell.id())
                                                .put("lexemeId", cell.lexemeId())
                                                .put("bundle", cell.bundle())
                                                .build())
                                        .toList()))
                        .put("excluded", built.excluded().size())
                        .build());
    }

    private static HttpResponse answer(DrillRouteDeps deps, AuthedRequest request) {
        requireDrill(deps, request);

        // A request with no body is refused exactly as a body of null is: neither is an object.
        JsonObject body = Codec.requireObject("body", request.body().orElse(Json.NULL));
        Optional<LocalDate> day = Query.dayFrom(request.query());
        String cellId = Codec.readString(body, "body", "cellId");
        String given = Codec.readString(body, "body", "given");
        AnswerOutcome outcome =
                deps.drill().answer(answerFor(request, day, cellId, given, AnswerBody.hintedFrom(body, "body")));
        return HttpResponse.json(200, AnswerBody.into(Json.object(), outcome).build());
    }

    private static HttpResponse finish(DrillRouteDeps deps, AuthedRequest request) {
        String learnerId = request.params().get("learnerId");
        requireDrill(deps, request);

        SessionSummary summary = deps.drill()
                .finish(learnerId, request.params().get("sessionId"))
                .summarise();
        return HttpResponse.json(
                200,
                Json.object()
                        .put("sessionId", summary.sessionId())
                        .put("answers", summary.tally().total())
                        .put("correct", summary.tally().correct())
                        .put("accuracy", summary.accuracy())
                        .build());
    }
}
