package io.github.rkaushikdev.stemwright.http.routes;

import static io.github.rkaushikdev.stemwright.support.Http.DIRECTORY;
import static io.github.rkaushikdev.stemwright.support.Http.httpHarness;
import static io.github.rkaushikdev.stemwright.support.Http.itemsAt;
import static io.github.rkaushikdev.stemwright.support.Http.makeRequest;
import static io.github.rkaushikdev.stemwright.support.Http.numberAt;
import static io.github.rkaushikdev.stemwright.support.Http.textAt;
import static io.github.rkaushikdev.stemwright.support.Http.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.app.Stores;
import io.github.rkaushikdev.stemwright.domain.errors.ForbiddenException;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.mastery.Attempt;
import io.github.rkaushikdev.stemwright.domain.ortho.HintLevel;
import io.github.rkaushikdev.stemwright.http.HttpRequest;
import io.github.rkaushikdev.stemwright.http.HttpResponse;
import io.github.rkaushikdev.stemwright.http.Method;
import io.github.rkaushikdev.stemwright.http.Route;
import io.github.rkaushikdev.stemwright.http.Router;
import io.github.rkaushikdev.stemwright.infra.json.Json;
import io.github.rkaushikdev.stemwright.infra.json.JsonObject;
import io.github.rkaushikdev.stemwright.support.Http;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BulkRoutesTest {

    private static final String QUEUE = "/learners/lrn_one/languages/latin/decks/all/queue";
    private static final String BULK = "/learners/lrn_one/languages/latin/decks/all/answers/bulk";
    private static final Map<String, String> COURSE = Map.of("course", "latin-1a");

    /** The harness's stores and a router over the drill and bulk routes together. */
    private record Built(Stores stores, Router handle) {}

    private static Built build() {
        Http.Harness harness = httpHarness();
        DrillRouteDeps deps = new DrillRouteDeps(DIRECTORY, harness.drill(), harness.scopeOf());
        List<Route> routes = new ArrayList<>(DrillRoutes.routes(deps));
        routes.addAll(BulkRoutes.routes(deps));
        return new Built(harness.stores(), Router.of(routes));
    }

    private static List<String> cellIds(Router handle, int count) {
        HttpResponse response = handle.handle(
                makeRequest(Method.GET, QUEUE, token("tok-student").withQuery(COURSE)));
        return itemsAt(response.body(), "cells").stream()
                .limit(count)
                .map(cell -> textAt(cell, "id"))
                .toList();
    }

    private static JsonObject row(String cellId, String given) {
        return Json.object().put("cellId", cellId).put("given", given).build();
    }

    private static JsonObject answers(List<? extends Json> rows) {
        return Json.object().put("answers", Json.array(rows)).build();
    }

    private static HttpResponse post(Router handle, List<? extends Json> rows) {
        return post(handle, rows, "tok-student");
    }

    private static HttpResponse post(Router handle, List<? extends Json> rows, String token) {
        return handle.handle(
                makeRequest(Method.POST, BULK, token(token).withQuery(COURSE).withBody(answers(rows))));
    }

    @Nested
    class ReadBatch {

        @Test
        void readsAListOfAnswers() {
            assertThat(BulkRoutes.readBatch(answers(List.of(row("cell_a", "x")))))
                    .containsExactly(new BulkAnswer("cell_a", "x", Optional.empty()));
        }

        @Test
        void refusesABodyThatIsNotAnObject() {
            assertThatThrownBy(() -> BulkRoutes.readBatch(Json.of("nope")))
                    .hasMessageContaining("body is not an object");
        }

        @Test
        void refusesAMissingOrEmptyList() {
            assertThatThrownBy(() -> BulkRoutes.readBatch(JsonObject.EMPTY))
                    .hasMessageContaining("body.answers is not an array");
            assertThatThrownBy(() -> BulkRoutes.readBatch(answers(List.of())))
                    .hasMessageContaining("at least one answer");
        }

        @Test
        void refusesAListOverTheCap() {
            List<JsonObject> many = Collections.nCopies(BulkRoutes.MAX_BATCH + 1, row("a", "b"));
            assertThatThrownBy(() -> BulkRoutes.readBatch(answers(many))).hasMessageContaining("at most 100 answers");
        }

        @Test
        void readsTheHintARowSaysTheLearnerSaw() {
            JsonObject hinted = Json.object()
                    .put("cellId", "cell_a")
                    .put("given", "x")
                    .put("hinted", "opening")
                    .build();
            assertThat(BulkRoutes.readBatch(answers(List.of(row("cell_b", "y"), hinted))))
                    .containsExactly(
                            new BulkAnswer("cell_b", "y", Optional.empty()),
                            new BulkAnswer("cell_a", "x", Optional.of(HintLevel.OPENING)));
        }

        @Test
        void refusesAHintNoRungIsCalledAndNamesItsRow() {
            JsonObject wrong = Json.object()
                    .put("cellId", "cell_a")
                    .put("given", "x")
                    .put("hinted", "all")
                    .build();
            assertThatThrownBy(() -> BulkRoutes.readBatch(answers(List.of(row("cell_b", "y"), wrong))))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(error -> assertThat(((ValidationException) error).details())
                            .extracting(detail -> detail.path() + " " + detail.message())
                            .containsExactly("body.answers.1.hinted is not a hint level: all"));
        }

        @Test
        void namesTheBadRow() {
            assertThatThrownBy(() -> BulkRoutes.readBatch(
                            answers(List.of(Json.object().put("cellId", "a").build()))))
                    .hasMessageContaining("body.answers.0.given is not a string");
            assertThatThrownBy(() -> BulkRoutes.readBatch(answers(List.of(Json.of("nope")))))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    class TheBulkRoute {

        @Test
        void answersEveryCellAndReports200WhenAllLanded() {
            Router handle = build().handle();
            List<String> ids = cellIds(handle, 3);
            HttpResponse response = post(
                    handle, ids.stream().map(cellId -> row(cellId, "nonsense")).toList());

            assertThat(response.status()).isEqualTo(200);
            assertThat(numberAt(response.body(), "accepted")).isEqualTo(3);
            assertThat(numberAt(response.body(), "refused")).isEqualTo(0);
            assertThat(itemsAt(response.body(), "results")).hasSize(3);
        }

        @Test
        void keepsGoingPastABadCellAndReports207() {
            Router handle = build().handle();
            List<String> ids = cellIds(handle, 2);
            HttpResponse response =
                    post(handle, List.of(row(ids.get(0), "x"), row("cell_zzzzzz", "x"), row(ids.get(1), "x")));

            assertThat(response.status()).isEqualTo(207);
            assertThat(numberAt(response.body(), "accepted")).isEqualTo(2);
            assertThat(numberAt(response.body(), "refused")).isEqualTo(1);
            assertThat(itemsAt(response.body(), "results").stream().map(result -> Http.at(result, "ok")))
                    .containsExactly(Json.of(true), Json.of(false), Json.of(true));
        }

        @Test
        void reportsTheVerdictAndTheExpectedFormOfEachAnswer() {
            Router handle = build().handle();
            List<String> ids = cellIds(handle, 1);
            HttpResponse response = post(handle, List.of(row(ids.get(0), "nonsense")));
            assertThat(textAt(response.body(), "results.0.verdict")).isEqualTo("wrong");
            assertThat(textAt(response.body(), "results.0.expected")).isNotEmpty();
        }

        @Test
        void givesEachAcceptedAnswerTheMissAndTheHintASingleAnswerWouldGet() {
            Router handle = build().handle();
            String cellId = cellIds(handle, 1).getFirst();
            HttpResponse response = post(handle, List.of(row(cellId, "zzzzzzzz"), row(cellId, "zzzzzzzz")));
            assertThat(textAt(response.body(), "results.0.miss")).isNotEmpty();
            assertThat(textAt(response.body(), "results.0.hint.level")).isEqualTo("shape");
            assertThat(textAt(response.body(), "results.1.hint.level")).isEqualTo("kind");
            assertThat(numberAt(response.body(), "results.1.sequence")).isEqualTo(2);
        }

        @Test
        void holdsTheRungOfARightAnswerTheBatchSaysWasCopied() {
            Built harness = build();
            Router handle = harness.handle();
            String cellId = cellIds(handle, 1).getFirst();
            String expected = textAt(post(handle, List.of(row(cellId, "x"))).body(), "results.0.expected");
            JsonObject copied = Json.object()
                    .put("cellId", cellId)
                    .put("given", expected)
                    .put("hinted", "reveal")
                    .build();
            HttpResponse response = post(handle, List.of(copied));
            assertThat(textAt(response.body(), "results.0.verdict")).isEqualTo("exact");
            assertThat(harness.stores()
                            .ledgers()
                            .get("lrn_one")
                            .orElseThrow()
                            .strengthFor(cellId)
                            .correct())
                    .isZero();
        }

        @Test
        void namesTheCellThatCouldNotBeAnswered() {
            Router handle = build().handle();
            HttpResponse response = post(handle, List.of(row("cell_zzzzzz", "x")));
            assertThat(textAt(response.body(), "results.0.cellId")).isEqualTo("cell_zzzzzz");
            assertThat(textAt(response.body(), "results.0.message")).contains("was not found");
        }

        @Test
        void recordsEveryAcceptedAnswerInOneSession() {
            Built built = build();
            List<String> ids = cellIds(built.handle(), 3);
            post(built.handle(), ids.stream().map(cellId -> row(cellId, "x")).toList());
            List<Attempt> attempts = built.stores().attempts().listByLearner("lrn_one");
            assertThat(attempts).hasSize(3);
            assertThat(attempts.stream().map(Attempt::sessionId).distinct()).hasSize(1);
        }

        @Test
        void refusesALearnerWhoIsNotEnrolled() {
            Router handle = build().handle();
            List<String> ids = cellIds(handle, 1);
            List<JsonObject> rows = ids.stream().map(cellId -> row(cellId, "x")).toList();
            assertThatThrownBy(() -> post(handle, rows, "tok-other")).isInstanceOf(ForbiddenException.class);
        }

        @Test
        void refusesARequestWithNoCourse() {
            Router handle = build().handle();
            HttpRequest request =
                    makeRequest(Method.POST, BULK, token("tok-student").withBody(answers(List.of(row("a", "b")))));
            assertThatThrownBy(() -> handle.handle(request)).hasMessageContaining("course must be given");
        }
    }
}
