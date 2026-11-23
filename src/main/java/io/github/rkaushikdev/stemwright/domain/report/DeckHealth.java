package io.github.rkaushikdev.stemwright.domain.report;

import io.github.rkaushikdev.stemwright.domain.drill.BurialSet;
import io.github.rkaushikdev.stemwright.domain.drill.DrillContext;
import io.github.rkaushikdev.stemwright.domain.paradigm.Cell;
import io.github.rkaushikdev.stemwright.domain.text.Numbers;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The health checks over the decks, which {@link Health#check} runs last. Every finding is in the
 * {@code decks} area.
 *
 * <p>A deck at a tolerance level the language never declared fails one answer later, so it is an
 * error here. A deck that selects nothing is an error too, and one that is uneven or small is worth
 * a word.
 *
 * <p>So is a deck whose cells are largely syncretic twins of each other. Burial lets a sitting ask
 * one cell of each twin set, so a first declension deck of ten cells per noun gives a sitting at
 * most seven questions per noun, and an editor sizing a deck by its cell count is promising the
 * class more practice than the desk will hand out.
 */
final class DeckHealth {

    private static final String DECKS = "decks";

    /** A deck whose busiest bundle holds more than this multiple of the mean is uneven. */
    private static final double UNEVEN = 2;

    /** A deck of fewer lexemes than this is worth a note. */
    private static final int FEW_LEXEMES = 5;

    /** Below this share of its cells left askable by burial, a deck is mostly twins. */
    private static final double MOSTLY_TWINS = 0.75;

    private static final int PERCENT = 100;

    private DeckHealth() {}

    static List<Finding> check(HealthInput input) {
        List<Finding> findings = new ArrayList<>();
        LocalDate on = input.on();
        for (HealthInput.ResolvedDeck resolved : input.decks()) {
            String deckKey = resolved.deck().key();
            resolved.deck()
                    .toleranceLevel()
                    .filter(level -> input.ortho().levelOf(level).isEmpty())
                    .ifPresent(level -> findings.add(new Finding(
                            FindingLevel.ERROR,
                            DECKS,
                            deckKey + " runs at the level " + level + ", which "
                                    + input.ortho().language()
                                    + " does not declare, so no answer to it can be marked")));
            DeckStats stats = DeckStats.of(resolved.deck(), resolved.membership(), List.of());
            if (stats.cells() == 0) {
                findings.add(new Finding(FindingLevel.ERROR, DECKS, deckKey + " selects nothing on " + on));
                continue;
            }
            double imbalance = stats.bundleImbalance();
            if (imbalance > UNEVEN) {
                findings.add(new Finding(
                        FindingLevel.WARNING,
                        DECKS,
                        deckKey + " is uneven, its busiest bundle holds " + Numbers.toFixed(imbalance, 1)
                                + " times the average"));
            }
            if (stats.lexemes() < FEW_LEXEMES) {
                findings.add(
                        new Finding(FindingLevel.NOTE, DECKS, deckKey + " holds only " + stats.lexemes() + " lexemes"));
            }
            checkTwins(input.context(), deckKey, resolved.membership().cells(), on, findings);
        }

        if (input.decks().isEmpty()) {
            findings.add(new Finding(FindingLevel.NOTE, DECKS, "no decks are defined"));
        }
        return List.copyOf(findings);
    }

    /**
     * Counts the twin sets among the deck's cells under the revisions in force on the day: each cell
     * with every twin of it the deck also takes is one question a sitting can ask, however many cells
     * the set holds.
     */
    private static void checkTwins(
            DrillContext context, String deckKey, List<Cell> cells, LocalDate on, List<Finding> findings) {
        Set<String> inDeck = new HashSet<>();
        cells.forEach(cell -> inDeck.add(cell.id()));
        Set<String> counted = new HashSet<>();
        int askable = 0;
        for (Cell cell : cells) {
            if (!counted.add(cell.id())) {
                continue;
            }
            askable += 1;
            BurialSet.twinCellsOf(context, cell, on).stream()
                    .filter(inDeck::contains)
                    .forEach(counted::add);
        }
        if (askable < cells.size() * MOSTLY_TWINS) {
            long share = Numbers.round((double) askable / cells.size() * PERCENT);
            findings.add(new Finding(
                    FindingLevel.NOTE,
                    DECKS,
                    deckKey + " takes " + cells.size() + " cells, but burial leaves a sitting " + askable
                            + " of them to ask, " + share + " percent, because the rest are twins"));
        }
    }
}
