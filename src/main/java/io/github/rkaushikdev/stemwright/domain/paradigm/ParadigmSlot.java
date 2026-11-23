package io.github.rkaushikdev.stemwright.domain.paradigm;

import java.util.List;
import java.util.Optional;

/**
 * The recipe for one cell of a paradigm profile: which stem to attach to, what to attach, and
 * whether the form is irregular enough that the profile just states it outright.
 *
 * @param bundle the canonical key of the bundle the slot fills, such as {@code case=gen;number=sg}
 * @param stemRole the role of the stem the affix attaches to
 * @param affix the ending, normalised; empty for a bare stem form
 * @param literal a full form that replaces stem plus affix, for a cell that is irregular
 * @param note an editor's remark on the slot
 * @param variants other endings the slot also accepts on the same stem, normalised, in the order
 *     written, such as the {@code is} a Latin i stem allows beside {@code es} in the accusative
 *     plural; always empty for a literal
 */
public record ParadigmSlot(
        String bundle,
        String stemRole,
        String affix,
        Optional<String> literal,
        Optional<String> note,
        List<String> variants) {

    public ParadigmSlot {
        variants = List.copyOf(variants);
    }
}
