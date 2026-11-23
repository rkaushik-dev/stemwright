package io.github.rkaushikdev.stemwright.domain.paradigm;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One cell of a printed paradigm table. A cell with no form is either blocked, because the lexeme's
 * inherent features rule the bundle out, or a hole the profile says nothing about.
 *
 * @param bundle the canonical bundle key, empty when no bundle of the schema fits the cell
 * @param form the realised form, absent for a blocked cell or a hole
 * @param blocked whether the cell has no answer for this lexeme
 * @param variants the other forms the slot accepts, printed after the form
 */
public record TableCell(String bundle, Optional<String> form, boolean blocked, List<String> variants) {

    private static final String VARIANT_MARK = "/";

    public TableCell {
        variants = List.copyOf(variants);
    }

    /** The form and its variants as a textbook prints them, one after another with a slash between. */
    Optional<String> printed() {
        return form.map(main -> String.join(
                VARIANT_MARK, Stream.concat(Stream.of(main), variants.stream()).toList()));
    }
}
