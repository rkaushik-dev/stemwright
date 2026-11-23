package io.github.rkaushikdev.stemwright.http.routes;

import static io.github.rkaushikdev.stemwright.support.Http.DIRECTORY;
import static io.github.rkaushikdev.stemwright.support.Http.at;
import static io.github.rkaushikdev.stemwright.support.Http.httpHarness;
import static io.github.rkaushikdev.stemwright.support.Http.itemsAt;
import static io.github.rkaushikdev.stemwright.support.Http.makeRequest;
import static io.github.rkaushikdev.stemwright.support.Http.numberAt;
import static io.github.rkaushikdev.stemwright.support.Http.textAt;
import static io.github.rkaushikdev.stemwright.support.Http.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.stemwright.domain.errors.ForbiddenException;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.http.HttpRequest;
import io.github.rkaushikdev.stemwright.http.HttpResponse;
import io.github.rkaushikdev.stemwright.http.Method;
import io.github.rkaushikdev.stemwright.http.Router;
import io.github.rkaushikdev.stemwright.infra.json.Json;
import io.github.rkaushikdev.stemwright.support.Book;
import io.github.rkaushikdev.stemwright.support.Drill;
import io.github.rkaushikdev.stemwright.support.Http;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DrillRoutesTest {

    private static final String QUEUE = "/learners/lrn_one/languages/latin/decks/all/queue";
    private static final String ANSWER = "/learners/lrn_one/languages/latin/decks/all/answers";
    private static final Map<String, String> COURSE = Map.of("course", "latin-1a");

    private static Router build() {
        Http.Harness harness = httpHarness();
        return Router.of(DrillRoutes.routes(new DrillRouteDeps(DIRECTORY, harness.drill(), harness.scopeOf())));
    }

    private static String firstCellId(Router handle) {
        HttpResponse response = handle.handle(
                makeRequest(Method.GET, QUEUE, token("tok-student").withQuery(COURSE)));
        return textAt(response.body(), "cells.0.id");
    }

    private static HttpResponse answer(Router handle, String cellId, String given) {
        return handle.handle(makeRequest(
                Method.POST,
                ANSWER,
                token("tok-student")
                        .withQuery(COURSE)
                        .withBody(Json.object()
                                .put("cellId", cellId)
                                .put("given", given)
                                .build())));
    }

    @Nested
    class TheQueueRoute {

        @Test
        void answersWithTheCellsToDrill() {
            Router handle = build();
            HttpResponse response = handle.handle(
                    makeRequest(Method.GET, QUEUE, token("tok-student").withQuery(COURSE)));
            assertThat(response.status()).isEqualTo(200);
            assertThat(itemsAt(response.body(), "cells")).isNotEmpty();
            assertThat(textAt(response.body(), "on")).isEqualTo("2024-10-21");
        }

        @Test
        void takesTheDayFromTheQueryWhenItIsGiven() {
            Router handle = build();
            HttpResponse response = handle.handle(makeRequest(
                    Method.GET,
                    QUEUE,
                    token("tok-student").withQuery(Map.of("course", "latin-1a", "on", "2024-11-04"))));
            assertThat(textAt(response.body(), "on")).isEqualTo("2024-11-04");
        }

        @Test
        void refusesADayThatIsNotARealDate() {
            Router handle = build();
            HttpRequest request = makeRequest(
                    Method.GET,
                    QUEUE,
                    token("tok-student").withQuery(Map.of("course", "latin-1a", "on", "2024-11-31")));
            assertThatThrownBy(() -> handle.handle(request)).isInstanceOf(ValidationException.class);
        }

        @Test
        void refusesARequestWithNoCourseToCheckPermissionsAgainst() {
            Router handle = build();
            HttpRequest request = makeRequest(Method.GET, QUEUE, token("tok-student"));
            assertThatThrownBy(() -> handle.handle(request)).hasMessageContaining("course must be given");
        }

        @Test
        void refusesALearnerWhoIsNotEnrolled() {
            Router handle = build();
            HttpRequest request =
                    makeRequest(Method.GET, QUEUE, token("tok-other").withQuery(COURSE));
            assertThatThrownBy(() -> handle.handle(request)).isInstanceOf(ForbiddenException.class);
        }

        @Test
        void refusesARequestWithNoToken() {
            Router handle = build();
            assertThatThrownBy(() -> handle.handle(makeRequest(Method.GET, QUEUE)))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    class TheAnswerRoute {

        @Test
        void marksAnAnswerAndReportsTheVerdict() {
            Router handle = build();
            HttpResponse response = answer(handle, firstCellId(handle), "nonsense");
            assertThat(response.status()).isEqualTo(200);
            assertThat(textAt(response.body(), "verdict")).isEqualTo("wrong");
            assertThat(textAt(response.body(), "expected")).isNotEmpty();
            assertThat(numberAt(response.body(), "sequence")).isEqualTo(1);
        }

        @Test
        void reportsARightAnswerAsRight() {
            Router handle = build();
            String cellId = firstCellId(handle);
            HttpResponse first = answer(handle, cellId, "x");
            String expected = textAt(first.body(), "expected");
            HttpResponse second = answer(handle, cellId, expected);
            assertThat(textAt(second.body(), "verdict")).isEqualTo("exact");
        }

        @Test
        void acceptsAVariantFormAndListsTheVariantsTheCellAllows() {
            Http.Harness harness = httpHarness();
            harness.stores().books().put(Book.LATIN_BOOK_WITH_OLD_GENITIVE);
            Router handle =
                    Router.of(DrillRoutes.routes(new DrillRouteDeps(DIRECTORY, harness.drill(), harness.scopeOf())));
            String genitive = Cell.idFor(Drill.PUELLA.id(), "case=gen;number=sg");

            HttpResponse response = answer(handle, genitive, "puellai");

            assertThat(textAt(response.body(), "verdict")).isEqualTo("exact");
            assertThat(textAt(response.body(), "expected")).isEqualTo("puellae");
            assertThat(itemsAt(response.body(), "variants")).containsExactly(Json.of("puellai"));
            assertThat(itemsAt(answer(build(), genitive, "puellai").body(), "variants"))
                    .isEmpty();
        }

        @Test
        void givesAWrongAnswerTheNextHintAndARightOneNone() {
            Router handle = build();
            String cellId = firstCellId(handle);
            HttpResponse first = answer(handle, cellId, "zzzzzzzz");
            HttpResponse second = answer(handle, cellId, "zzzzzzzz");
            HttpResponse right = answer(handle, cellId, textAt(first.body(), "expected"));

            assertThat(textAt(first.body(), "hint.level")).isEqualTo("shape");
            assertThat(textAt(first.body(), "hint.text"))
                    .endsWith(".".repeat(textAt(first.body(), "expected").length()));
            assertThat(textAt(second.body(), "hint.level")).isEqualTo("kind");
            assertThat(at(right.body(), "hint")).isEqualTo(Json.NULL);
        }

        @Test
        void readsTheHintTheLearnerSawFromTheBody() {
            Router handle = build();
            String cellId = firstCellId(handle);
            String expected = textAt(answer(handle, cellId, "x").body(), "expected");
            HttpResponse response = handle.handle(makeRequest(
                    Method.POST,
                    ANSWER,
                    token("tok-student")
                            .withQuery(COURSE)
                            .withBody(Json.object()
                                    .put("cellId", cellId)
                                    .put("given", expected)
                                    .put("hinted", "reveal")
                                    .build())));
            assertThat(textAt(response.body(), "verdict")).isEqualTo("exact");
        }

        @Test
        void refusesAHintLevelNobodyDeclared() {
            Router handle = build();
            HttpRequest request = makeRequest(
                    Method.POST,
                    ANSWER,
                    token("tok-student")
                            .withQuery(COURSE)
                            .withBody(Json.object()
                                    .put("cellId", firstCellId(handle))
                                    .put("given", "x")
                                    .put("hinted", "everything")
                                    .build()));
            assertThatThrownBy(() -> handle.handle(request))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(error -> assertThat(((ValidationException) error).details())
                            .extracting(detail -> detail.path() + " " + detail.message())
                            .containsExactly("body.hinted is not a hint level: everything"));
        }

        @Test
        void refusesABodyThatIsNotAnObject() {
            Router handle = build();
            HttpRequest request = makeRequest(
                    Method.POST, ANSWER, token("tok-student").withQuery(COURSE).withBody(Json.of("nope")));
            assertThatThrownBy(() -> handle.handle(request)).hasMessageContaining("body is not an object");
        }

        @Test
        void refusesABodyWithNoCellId() {
            Router handle = build();
            HttpRequest request = makeRequest(
                    Method.POST,
                    ANSWER,
                    token("tok-student")
                            .withQuery(COURSE)
                            .withBody(Json.object().put("given", "x").build()));
            assertThatThrownBy(() -> handle.handle(request)).hasMessageContaining("body.cellId is not a string");
        }
    }

    @Nested
    class TheFinishRoute {

        private HttpResponse finish(Router handle, String sessionId) {
            return handle.handle(makeRequest(
                    Method.POST,
                    "/learners/lrn_one/sessions/" + sessionId + "/finish",
                    token("tok-student").withQuery(COURSE)));
        }

        @Test
        void closesASessionAndReportsTheTally() {
            Router handle = build();
            String cellId = firstCellId(handle);
            HttpResponse answered = answer(handle, cellId, "x");

            HttpResponse response = finish(handle, textAt(answered.body(), "sessionId"));
            assertThat(response.status()).isEqualTo(200);
            assertThat(numberAt(response.body(), "answers")).isEqualTo(1);
            assertThat(itemsAt(response.body(), "unresolved")).containsExactly(Json.of(cellId));
        }

        @Test
        void reportsTheFirstTryShareApartFromTheOverallOne() {
            Router handle = build();
            List<String> cells = itemsAt(
                            handle.handle(makeRequest(
                                            Method.GET,
                                            QUEUE,
                                            token("tok-student").withQuery(COURSE)))
                                    .body(),
                            "cells")
                    .stream()
                    .map(cell -> textAt(cell, "id"))
                    .toList();
            HttpResponse missed = answer(handle, cells.get(0), "x");
            answer(handle, cells.get(0), textAt(missed.body(), "expected"));
            HttpResponse other = answer(handle, cells.get(1), "x");
            answer(handle, cells.get(1), textAt(other.body(), "expected"));

            HttpResponse response = finish(handle, textAt(missed.body(), "sessionId"));
            assertThat(numberAt(response.body(), "accuracy")).isEqualTo(0.5);
            assertThat(numberAt(response.body(), "firstTryAccuracy")).isZero();
            assertThat(itemsAt(response.body(), "unresolved")).isEmpty();
        }
    }

    @Nested
    class Routing {

        @Test
        void answers404ForAPathNothingMatches() {
            Router handle = build();
            assertThat(handle.handle(makeRequest(Method.GET, "/nowhere")).status())
                    .isEqualTo(404);
        }

        @Test
        void answers405ForTheWrongMethodOnAKnownPath() {
            Router handle = build();
            assertThat(handle.handle(makeRequest(Method.DELETE, QUEUE)).status())
                    .isEqualTo(405);
        }
    }
}
