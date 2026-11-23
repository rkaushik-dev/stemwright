package io.github.rkaushikdev.stemwright.domain.paradigm;

import io.github.rkaushikdev.stemwright.domain.errors.ValidationException;
import io.github.rkaushikdev.stemwright.domain.features.FeatureBundle;
import io.github.rkaushikdev.stemwright.domain.features.FeatureDimension;
import io.github.rkaushikdev.stemwright.domain.features.FeatureSchema;
import io.github.rkaushikdev.stemwright.domain.features.FeatureValue;
import io.github.rkaushikdev.stemwright.domain.lexicon.Lexeme;
import io.github.rkaushikdev.stemwright.domain.text.Text;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A paradigm is learnt as a grid, not as a list. The grid is chosen by picking one dimension for the
 * rows and one for the columns, which is what a textbook does when it prints case down the side and
 * number across the top.
 *
 * <p>Any dimension the grid does not use has to be fixed to one value, otherwise a cell of the grid
 * would hold several forms and the table would be lying about which one it is showing.
 *
 * @param rowDimension the dimension key for the rows
 * @param columnDimension the dimension key for the columns
 * @param rowValues the row value keys, in schema order
 * @param columnValues the column value keys, in schema order
 * @param fixed the values the other dimensions are held at
 * @param cells the cells, one list per row, each in column order
 */
public record ParadigmTable(
        String rowDimension,
        String columnDimension,
        List<String> rowValues,
        List<String> columnValues,
        FeatureBundle fixed,
        List<List<TableCell>> cells) {

    /** What a cell with no form is printed as unless the caller names another blank. */
    public static final String BLANK = "-";

    private static final String COLUMN_GAP = "  ";

    public ParadigmTable {
        rowValues = List.copyOf(rowValues);
        columnValues = List.copyOf(columnValues);
        cells = cells.stream().map(List::copyOf).toList();
    }

    /**
     * Lays the lexeme's forms out on the requested grid. A grid position no bundle of the schema
     * fits is blocked with an empty bundle key.
     *
     * @throws ValidationException when the rows and columns name one dimension, when either is not
     *     in the schema, or when a required dimension the grid does not use is left unfixed
     */
    public static ParadigmTable build(
            FeatureSchema schema, RuleSet rules, Lexeme entry, ParadigmProfile profile, TableRequest request) {
        if (request.rowDimension().equals(request.columnDimension())) {
            throw ValidationException.field("table", "cannot use one dimension for both rows and columns");
        }
        FeatureDimension rows = schema.requireDimension(request.rowDimension());
        FeatureDimension columns = schema.requireDimension(request.columnDimension());
        Map<String, String> fixed = request.fixed().map(FeatureBundle::toInput).orElseGet(Map::of);

        for (FeatureDimension dimension : schema.dimensions()) {
            if (dimension.key().equals(rows.key()) || dimension.key().equals(columns.key()) || dimension.optional()) {
                continue;
            }
            if (!fixed.containsKey(dimension.key())) {
                throw ValidationException.field(
                        "table", "leaves " + dimension.key() + " open, which would put several forms in one cell");
            }
        }

        List<FeatureBundle> candidates = FeatureBundle.enumerate(schema);
        List<List<TableCell>> cells = new ArrayList<>();
        for (FeatureValue rowValue : rows.values()) {
            List<TableCell> row = new ArrayList<>();
            for (FeatureValue columnValue : columns.values()) {
                Map<String, String> wanted = new LinkedHashMap<>(fixed);
                wanted.put(rows.key(), rowValue.key());
                wanted.put(columns.key(), columnValue.key());
                Optional<FeatureBundle> found =
                        candidates.stream().filter(fixing(wanted)).findFirst();
                if (found.isEmpty()) {
                    row.add(new TableCell("", Optional.empty(), true, List.of()));
                    continue;
                }
                FeatureBundle bundle = found.get();
                String key = bundle.key();
                boolean blocked = entry.contradictsInherent(bundle);
                Optional<RealisedCell> realised = blocked
                        ? Optional.empty()
                        : Optional.ofNullable(profile.slots().get(key)).map(slot -> rules.realiseSlot(entry, slot));
                row.add(new TableCell(
                        key,
                        realised.map(RealisedCell::form),
                        blocked,
                        realised.map(RealisedCell::variants).orElse(List.of())));
            }
            cells.add(row);
        }

        return new ParadigmTable(
                rows.key(),
                columns.key(),
                rows.values().stream().map(FeatureValue::key).toList(),
                columns.values().stream().map(FeatureValue::key).toList(),
                request.fixed().orElse(FeatureBundle.EMPTY),
                cells);
    }

    private static Predicate<FeatureBundle> fixing(Map<String, String> wanted) {
        return candidate -> wanted.entrySet().stream().allMatch(pair -> candidate
                .valueOf(pair.getKey())
                .filter(pair.getValue()::equals)
                .isPresent());
    }

    /** The cell at a row value and a column value, or nothing for a value the grid does not have. */
    public Optional<TableCell> cell(String rowValue, String columnValue) {
        int rowIndex = rowValues.indexOf(rowValue);
        int columnIndex = columnValues.indexOf(columnValue);
        if (rowIndex < 0 || columnIndex < 0 || rowIndex >= cells.size()) {
            return Optional.empty();
        }
        List<TableCell> row = cells.get(rowIndex);
        return columnIndex < row.size() ? Optional.of(row.get(columnIndex)) : Optional.empty();
    }

    /** How many cells hold a form. */
    public int filledCount() {
        return (int) allCells().filter(cell -> cell.form().isPresent()).count();
    }

    /** How many cells the lexeme's inherent features rule out. */
    public int blockedCount() {
        return (int) allCells().filter(TableCell::blocked).count();
    }

    /** How many cells the profile says nothing about. */
    public int holeCount() {
        return (int) allCells()
                .filter(cell -> cell.form().isEmpty() && !cell.blocked())
                .count();
    }

    private Stream<TableCell> allCells() {
        return cells.stream().flatMap(List::stream);
    }

    /** Rows whose every filled cell holds the same form, which is worth flagging. */
    public List<String> flatRows() {
        List<String> flat = new ArrayList<>();
        for (int index = 0; index < cells.size(); index += 1) {
            List<String> forms = cells.get(index).stream()
                    .flatMap(cell -> cell.form().stream())
                    .toList();
            if (forms.size() > 1 && new HashSet<>(forms).size() == 1) {
                flat.add(rowValues.get(index));
            }
        }
        return flat;
    }

    /** {@link #render(String)} with {@link #BLANK} for a cell with no form. */
    public List<List<String>> render() {
        return render(BLANK);
    }

    /**
     * The table as rows of text: a header of column values after an empty corner, then each row
     * value followed by its forms, each with any variants after a slash, the blank standing in for a
     * cell with no form.
     */
    public List<List<String>> render(String blank) {
        List<List<String>> grid = new ArrayList<>();
        List<String> header = new ArrayList<>();
        header.add("");
        header.addAll(columnValues);
        grid.add(header);
        for (int index = 0; index < cells.size(); index += 1) {
            List<String> line = new ArrayList<>();
            line.add(rowValues.get(index));
            cells.get(index).forEach(cell -> line.add(cell.printed().orElse(blank)));
            grid.add(line);
        }
        return grid.stream().map(List::copyOf).toList();
    }

    /** {@link #toText(String)} with {@link #BLANK} for a cell with no form. */
    public String toText() {
        return toText(BLANK);
    }

    /**
     * The rendered table as lines of text, every column padded to its widest value, two spaces
     * between columns and no trailing space on any line.
     */
    public String toText(String blank) {
        List<List<String>> grid = render(blank);
        int[] widths = new int[grid.get(0).size()];
        for (List<String> line : grid) {
            for (int column = 0; column < Math.min(line.size(), widths.length); column += 1) {
                widths[column] = Math.max(widths[column], line.get(column).length());
            }
        }
        return grid.stream().map(line -> padded(line, widths)).collect(Collectors.joining("\n"));
    }

    /** One line of text, each value padded to the width of its column, with the end trimmed. */
    private static String padded(List<String> line, int[] widths) {
        List<String> values = new ArrayList<>(line.size());
        for (int column = 0; column < line.size(); column += 1) {
            String value = line.get(column);
            values.add(column < widths.length ? Text.padEnd(value, widths[column]) : value);
        }
        return Text.trimEnd(String.join(COLUMN_GAP, values));
    }
}
