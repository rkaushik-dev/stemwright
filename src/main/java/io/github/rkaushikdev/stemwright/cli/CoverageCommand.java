package io.github.rkaushikdev.stemwright.cli;

import io.github.rkaushikdev.stemwright.app.Application;
import io.github.rkaushikdev.stemwright.domain.drill.Membership;
import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexicon;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.LedgerCounts;
import io.github.rkaushikdev.stemwright.domain.mastery.MasteryLedger;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.report.Coverage;
import io.github.rkaushikdev.stemwright.domain.report.CoverageRow;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * How a learner's ledger stands over a deck on a day, and the rows that need the most work.
 *
 * <p>Coverage has to be read in two directions because they disagree, so {@code --by} picks one:
 * down the bundles, which says which cells of the table are weak across every word, or down the
 * lexemes, which says which words are weak across the whole table. {@code --ladder} reads maturity
 * against the ladder the learner actually climbs, since a cell at the top of the slow start ladder
 * is three rungs short of the top of the standard one.
 */
final class CoverageCommand {

    /** How wide the maturity bar of a coverage row is drawn. */
    private static final int BAR_WIDTH = 12;

    private static final int WEAKEST_DEFAULT = 5;

    private CoverageCommand() {}

    static Command of(Application app) {
        return Command.of(
                "coverage",
                "show how much of a deck a learner holds",
                "stemwright coverage --language latin --deck all --learner lrn_one --by lexeme",
                FlagSpecs.of(
                        Map.entry("language", Flags.LANGUAGE),
                        Map.entry("deck", Flags.DECK),
                        Map.entry("learner", Flags.LEARNER),
                        Map.entry("on", Flags.DAY),
                        Map.entry("by", FlagSpec.of(FlagKind.STRING, "bundle or lexeme, the rows to rank")),
                        Map.entry("ladder", FlagSpec.of(FlagKind.STRING, "the ladder key maturity is read against")),
                        Map.entry("limit", FlagSpec.of(FlagKind.NUMBER, "how many of the weakest rows to show"))),
                (args, context) -> show(app, args));
    }

    private static String show(Application app, ParsedArgs args) {
        String language = args.requireFlag("language");
        String learnerId = args.requireFlag("learner");
        LocalDate on = Flags.dayFrom(app.clock(), args);
        boolean byLexeme = byLexeme(args);
        Ladder ladder = args.stringFlag("ladder").map(Ladder::of).orElse(Ladder.STANDARD);
        int limit = Flags.countFrom(args, "limit", WEAKEST_DEFAULT);

        Membership membership = app.drill().membership(language, args.requireFlag("deck"), on);
        MasteryLedger ledger = app.stores().ledgers().get(learnerId).orElseGet(() -> MasteryLedger.empty(learnerId));
        List<Cell> cells = membership.cells();
        LedgerCounts counts = ledger.counts(cells.stream().map(Cell::id).toList(), ladder, on);
        List<CoverageRow> rows =
                byLexeme ? Coverage.byLexeme(cells, ledger, ladder, on) : Coverage.byBundle(cells, ledger, ladder, on);
        Lexicon lexicon = app.drill().context(language).lexicon();

        String summary = Render.keyValues(List.of(
                Map.entry("cells", String.valueOf(counts.known())),
                Map.entry("introduced", String.valueOf(counts.introduced())),
                Map.entry("mature", String.valueOf(counts.mature())),
                Map.entry("due", String.valueOf(counts.due())),
                Map.entry("suspended", String.valueOf(counts.suspended())),
                Map.entry("ladder", ladder.key())));
        String weakest = Render.table(
                List.of(
                        Column.of(byLexeme ? "lemma" : "bundle"),
                        Column.of("maturity"),
                        Column.of("share", Align.RIGHT)),
                Coverage.weakestRows(rows, limit).stream()
                        .map(row -> List.of(
                                byLexeme ? lemmaOf(lexicon, row.key()) : row.key(),
                                Render.bar(row.maturity(), BAR_WIDTH),
                                Render.percent(row.maturity())))
                        .toList());
        return String.join("\n", summary, "", weakest);
    }

    private static boolean byLexeme(ParsedArgs args) {
        String by = args.stringFlag("by").orElse("bundle");
        return switch (by) {
            case "bundle" -> false;
            case "lexeme" -> true;
            default -> throw ValidationException.field("by", "must be bundle or lexeme, not " + by);
        };
    }

    private static String lemmaOf(Lexicon lexicon, String lexemeId) {
        return lexicon.lexemeById(lexemeId).map(Lexeme::citationForm).orElse(lexemeId);
    }
}
