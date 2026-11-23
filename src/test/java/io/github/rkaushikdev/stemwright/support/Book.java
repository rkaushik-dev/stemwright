package io.github.rkaushikdev.stemwright.support;

import static io.github.rkaushikdev.stemwright.support.Profiles.FIRST_DECLENSION;
import static io.github.rkaushikdev.stemwright.support.Profiles.LATIN_NOUN;
import static io.github.rkaushikdev.stemwright.support.Profiles.SECOND_NEUTER;

import io.github.rkaushikdev.stemwright.domain.paradigm.ParadigmSlotInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileBook;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileBookInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.ProfileRevisionInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.RevisionHistory;
import io.github.rkaushikdev.stemwright.domain.paradigm.SyncretismGroupInput;
import io.github.rkaushikdev.stemwright.domain.paradigm.SyncretismMap;
import io.github.rkaushikdev.stemwright.domain.time.CivilDates;
import java.time.LocalDate;
import java.util.List;

/**
 * The Latin profile book the tests share: the first declension with one revision, the second
 * declension neuter with two, bound to {@code first-declension} and {@code second-declension}, so
 * the third declension and both conjugations are unbound.
 */
public final class Book {

    private Book() {}

    /** One revision from 2024-09-01, declaring the ae and is syncretisms. */
    public static final RevisionHistory FIRST_HISTORY = RevisionHistory.of(
            "first-declension",
            List.of(ProfileRevisionInput.of(day("2024-09-01"), FIRST_DECLENSION)
                    .withSyncretism(SyncretismMap.of(
                            LATIN_NOUN,
                            List.of(
                                    SyncretismGroupInput.of("ae", "gen.sg", "dat.sg", "nom.pl"),
                                    SyncretismGroupInput.of("is", "dat.pl", "abl.pl"))))));

    /**
     * Two revisions of the same profile: one from 2024-09-01 declaring nothing, and one from
     * 2025-02-01 declaring the neuter nominative and accusative rule in both numbers.
     */
    public static final RevisionHistory NEUTER_HISTORY = RevisionHistory.of(
            "second-neuter",
            List.of(
                    ProfileRevisionInput.of(day("2024-09-01"), SECOND_NEUTER),
                    ProfileRevisionInput.of(day("2025-02-01"), SECOND_NEUTER)
                            .withSyncretism(SyncretismMap.of(
                                    LATIN_NOUN,
                                    List.of(
                                            SyncretismGroupInput.of("neuter-rule", "nom.sg", "acc.sg"),
                                            SyncretismGroupInput.of("neuter-plural", "nom.pl", "acc.pl"))))
                            .withReason("the neuter rule was made explicit for the second year syllabus")));

    /** Both histories, the second declension bound at the parent so its children inherit it. */
    public static final ProfileBook LATIN_BOOK = ProfileBook.of(new ProfileBookInput(
            "latin",
            List.of(FIRST_HISTORY, NEUTER_HISTORY),
            TestMaps.ordered("first-declension", "first-declension", "second-declension", "second-neuter")));

    /**
     * {@link #LATIN_BOOK} with the first declension also accepting the old genitive in {@code ai}
     * beside {@code ae}, as Virgil still writes it, and the same syncretisms.
     */
    public static final ProfileBook LATIN_BOOK_WITH_OLD_GENITIVE = ProfileBook.of(new ProfileBookInput(
            "latin",
            List.of(
                    RevisionHistory.of(
                            "first-declension",
                            List.of(ProfileRevisionInput.of(
                                            day("2024-09-01"),
                                            FIRST_DECLENSION.withSlot(
                                                    LATIN_NOUN, ParadigmSlotInput.of("gen.sg", "ae", "ai")))
                                    .withSyncretism(
                                            FIRST_HISTORY.revisions().getFirst().syncretism()))),
                    NEUTER_HISTORY),
            TestMaps.ordered("first-declension", "first-declension", "second-declension", "second-neuter")));

    private static LocalDate day(String value) {
        return CivilDates.parseCivilDate(value);
    }
}
