package io.github.rkaushikdev.stemwright.http.routes;

import io.github.rkaushikdev.stemwright.app.services.ActivityReport;
import io.github.rkaushikdev.stemwright.app.services.LearnerReport;
import io.github.rkaushikdev.stemwright.domain.course.CourseScope;
import io.github.rkaushikdev.stemwright.domain.mastery.LedgerCounts;
import io.github.rkaushikdev.stemwright.http.Auth;
import io.github.rkaushikdev.stemwright.http.AuthedRequest;
import io.github.rkaushikdev.stemwright.http.HttpResponse;
import io.github.rkaushikdev.stemwright.http.Method;
import io.github.rkaushikdev.stemwright.http.Query;
import io.github.rkaushikdev.stemwright.http.Route;
import io.github.rkaushikdev.stemwright.infra.json.Json;
import io.github.rkaushikdev.stemwright.infra.json.JsonObject;
import java.util.List;

/**
 * Report routes. Both of them need a window, and the window has to be given rather than defaulted:
 * a report over "the last month" means something different every day it is run, and a link to one
 * that quietly changes what it shows is worse than a link that refuses to open.
 *
 * <p>Each route needs the {@code course} query parameter, and reads it before the window, because
 * the course is what the service checks the caller against.
 */
public final class ReportRoutes {

    private ReportRoutes() {}

    /**
     * {@code GET .../decks/:deckKey/coverage} and {@code GET /learners/:learnerId/activity}, each
     * behind {@link Auth#withActor}.
     */
    public static List<Route> routes(ReportRouteDeps deps) {
        return List.of(
                Route.of(
                        Method.GET,
                        "/learners/:learnerId/languages/:language/decks/:deckKey/coverage",
                        Auth.withActor(deps.directory(), request -> coverage(deps, request))),
                Route.of(
                        Method.GET,
                        "/learners/:learnerId/activity",
                        Auth.withActor(deps.directory(), request -> activity(deps, request))));
    }

    private static HttpResponse coverage(ReportRouteDeps deps, AuthedRequest request) {
        CourseScope scope = deps.scopeOf().apply(Query.requireCourseKey(request.query()));
        LearnerReport report = deps.reports()
                .learner(
                        request.actor(),
                        scope,
                        request.params().get("learnerId"),
                        request.params().get("language"),
                        request.params().get("deckKey"),
                        Query.rangeFrom(request.query()));

        return HttpResponse.json(
                200,
                Json.object()
                        .put("learnerId", report.learnerId())
                        .put("deckKey", report.deckKey())
                        .put("on", report.on().toString())
                        .put("counts", countsJson(report.counts()))
                        .put(
                                "weakest",
                                Json.array(report.weakest().stream()
                                        .map(row -> Json.object()
                                                .put("key", row.key())
                                                .put("total", row.total())
                                                .put("mature", row.mature())
                                                .put("maturity", row.maturity())
                                                .build())
                                        .toList()))
                        .put(
                                "forecast",
                                Json.array(report.forecast().stream()
                                        .map(day -> Json.object()
                                                .put("on", day.on().toString())
                                                .put("due", day.due())
                                                .put("carried", day.carried())
                                                .put("asked", day.asked())
                                                .build())
                                        .toList()))
                        .build());
    }

    private static HttpResponse activity(ReportRouteDeps deps, AuthedRequest request) {
        CourseScope scope = deps.scopeOf().apply(Query.requireCourseKey(request.query()));
        ActivityReport report = deps.reports()
                .activity(request.actor(), scope, request.params().get("learnerId"), Query.rangeFrom(request.query()));

        return HttpResponse.json(
                200,
                Json.object()
                        .put("learnerId", report.learnerId())
                        .put("from", report.range().from().toString())
                        .put("to", report.range().to().toString())
                        .put(
                                "days",
                                Json.array(report.days().stream()
                                        .map(day -> Json.object()
                                                .put("on", day.on().toString())
                                                .put("answers", day.tally().total())
                                                .put("correct", day.tally().correct())
                                                .put("accuracy", day.accuracy())
                                                .build())
                                        .toList()))
                        .put(
                                "hardest",
                                Json.array(report.hardest().stream()
                                        .map(row -> Json.object()
                                                .put("cellId", row.cellId())
                                                .put("wrong", row.tally().wrong())
                                                .put("accuracy", row.accuracy())
                                                .build())
                                        .toList()))
                        .build());
    }

    /** The counts in the order the ledger reports them. */
    private static JsonObject countsJson(LedgerCounts counts) {
        return Json.object()
                .put("known", counts.known())
                .put("introduced", counts.introduced())
                .put("due", counts.due())
                .put("mature", counts.mature())
                .put("suspended", counts.suspended())
                .put("untouched", counts.untouched())
                .build();
    }
}
