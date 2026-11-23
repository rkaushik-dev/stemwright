package io.github.rkaushikdev.stemwright.cli;

import io.github.rkaushikdev.stemwright.app.Application;
import io.github.rkaushikdev.stemwright.domain.drill.ExclusionReason;
import io.github.rkaushikdev.stemwright.domain.drill.Queue;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexicon;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * What a learner would be asked on a day, and with {@code --explain} why the rest of the deck was
 * left out.
 *
 * <p>The explanation is the half an instructor actually wants. A learner who writes in to say the
 * queue is short today is told nothing by the ten cells they were asked, and everything by the
 * forty that hit the review budget or were buried behind a twin answered an hour earlier.
 */
final class QueueCommand {

    private QueueCommand() {}

    /** {@code queue}: the cells a learner would be asked on a day, today unless {@code --on} says. */
    static Command of(Application app) {
        return Command.of(
                "queue",
                "show what a learner would be asked today",
                "stemwright queue --language latin --deck all --learner lrn_one --explain",
                FlagSpecs.of(
                        Map.entry("language", Flags.LANGUAGE),
                        Map.entry("deck", Flags.DECK),
                        Map.entry("learner", Flags.LEARNER),
                        Map.entry("on", Flags.DAY),
                        Map.entry(
                                "explain",
                                FlagSpec.of(FlagKind.BOOLEAN, "say why every cell that is not asked was left out"))),
                (args, context) -> show(app, args));
    }

    private static String show(Application app, ParsedArgs args) {
        String language = args.requireFlag("language");
        LocalDate on = Flags.dayFrom(app.clock(), args);
        Queue queue = app.drill().queue(args.requireFlag("learner"), language, args.requireFlag("deck"), on);
        Lexicon lexicon = app.drill().context(language).lexicon();

        String asked = Render.table(
                List.of(Column.of("lemma"), Column.of("bundle"), Column.of("kind"), Column.of("overdue", Align.RIGHT)),
                queue.entries().stream()
                        .map(entry -> List.of(
                                lexicon.lexemeById(entry.cell().lexemeId())
                                        .map(Lexeme::citationForm)
                                        .orElse(entry.cell().lexemeId()),
                                entry.cell().bundle(),
                                entry.kind().key(),
                                String.valueOf(entry.overdueDays())))
                        .toList());
        if (!args.booleanFlag("explain")) {
            return asked;
        }
        return String.join("\n", asked, "", explain(queue));
    }

    /**
     * The cells left out, counted by reason in the order the reasons are declared, with a reason
     * nothing was left out for not listed at all, under a line saying how much of the deck was asked.
     */
    private static String explain(Queue queue) {
        List<List<String>> rows = new ArrayList<>();
        Arrays.stream(ExclusionReason.values()).forEach(reason -> {
            int count = queue.excludedFor(reason).size();
            if (count > 0) {
                rows.add(List.of(reason.key(), String.valueOf(count)));
            }
        });
        int deck = queue.length() + queue.excluded().size();
        String heading =
                "Left out: " + queue.excluded().size() + " of " + Render.pluralise(deck, "cell") + " on " + queue.on();
        return Render.section(
                heading,
                rows.isEmpty()
                        ? ""
                        : Render.table(List.of(Column.of("reason"), Column.of("cells", Align.RIGHT)), rows));
    }
}
