package io.github.rkaushikdev.stemwright.domain.drill;

import io.github.rkaushikdev.stemwright.domain.mastery.CreditRule;
import io.github.rkaushikdev.stemwright.domain.mastery.Ladder;
import io.github.rkaushikdev.stemwright.domain.mastery.LeechPolicy;
import io.github.rkaushikdev.stemwright.domain.ortho.OrthoProfile;
import io.github.rkaushikdev.stemwright.domain.ortho.TransliterationTable;
import io.github.rkaushikdev.stemwright.domain.paradigm.RuleSet;
import java.util.Objects;

/**
 * The rules a review marks and schedules an answer by, which come from the language and the
 * course rather than from the answer.
 *
 * @param ortho the language's tolerance levels
 * @param rules the language's join rules, which realise the expected form
 * @param ladder the interval ladder the cell climbs
 * @param leech what happens to a cell that keeps lapsing
 * @param credit how much a right answer passes to the cell's twins
 * @param buryTwins whether an answer, right or wrong, buries the cell and its twins for the rest of
 *     the sitting
 * @param transliteration the table an answer typed without the language's keyboard is read through,
 *     {@link TransliterationTable#NONE} for a language that declares none
 */
public record ReviewSettings(
        OrthoProfile ortho,
        RuleSet rules,
        Ladder ladder,
        LeechPolicy leech,
        CreditRule credit,
        boolean buryTwins,
        TransliterationTable transliteration) {

    public ReviewSettings {
        Objects.requireNonNull(ortho, "ortho");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(ladder, "ladder");
        Objects.requireNonNull(leech, "leech");
        Objects.requireNonNull(credit, "credit");
        Objects.requireNonNull(transliteration, "transliteration");
    }

    /** Settings that pass the {@link CreditRule#DEFAULT} credit, bury twins and transliterate nothing. */
    public static ReviewSettings of(OrthoProfile ortho, RuleSet rules, Ladder ladder, LeechPolicy leech) {
        return of(ortho, rules, ladder, leech, TransliterationTable.NONE);
    }

    /** Settings that pass the {@link CreditRule#DEFAULT} credit, bury twins and read through the table. */
    public static ReviewSettings of(
            OrthoProfile ortho, RuleSet rules, Ladder ladder, LeechPolicy leech, TransliterationTable table) {
        return new ReviewSettings(ortho, rules, ladder, leech, CreditRule.DEFAULT, true, table);
    }

    /** A copy under another leech policy. */
    public ReviewSettings withLeech(LeechPolicy policy) {
        return new ReviewSettings(ortho, rules, ladder, policy, credit, buryTwins, transliteration);
    }

    /** A copy passing credit by another rule. */
    public ReviewSettings withCredit(CreditRule rule) {
        return new ReviewSettings(ortho, rules, ladder, leech, rule, buryTwins, transliteration);
    }

    /** A copy that buries twins, or leaves the burial set alone, as told. */
    public ReviewSettings withBuryTwins(boolean bury) {
        return new ReviewSettings(ortho, rules, ladder, leech, credit, bury, transliteration);
    }
}
