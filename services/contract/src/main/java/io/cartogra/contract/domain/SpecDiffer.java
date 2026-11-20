package io.cartogra.contract.domain;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import io.cartogra.contract.domain.DiffLine.Kind;
import io.cartogra.contract.domain.SpecChange.Op;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Compares two canonical specs and gives three results. The first is a structural diff of the JSON trees (objects key
 * by key, arrays by index). The second is a side-by-side line diff of their YAML renderings. The third is the
 * compatibility verdict of the {@link BreakingChangeEngine}. The differ parses each spec once for the three results.
 * Canonical specs have sorted keys, so a change of key order or of format is not a diff.
 */
@Component
public class SpecDiffer {

    private final ObjectMapper json = JsonMapper.builder().build();
    private final ObjectMapper yaml = YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .build();
    private final BreakingChangeEngine engine;

    public SpecDiffer(BreakingChangeEngine engine) {
        this.engine = engine;
    }

    public VersionDiff diff(@Nullable ContractVersion from, ContractVersion to) {
        JsonNode toTree = json.readTree(to.specCanonical());
        JsonNode fromTree = from == null ? null : json.readTree(from.specCanonical());
        return new VersionDiff(from == null ? null : VersionHeader.of(from), VersionHeader.of(to),
                changes(fromTree, toTree), lines(fromTree == null ? "" : toYaml(fromTree), toYaml(toTree)),
                engine.compare(fromTree, toTree));
    }

    /** Structural changes from {@code from} to {@code to}; a null {@code from} reports every top-level key as added. */
    public List<SpecChange> changes(@Nullable JsonNode from, JsonNode to) {
        List<SpecChange> out = new ArrayList<>();
        if (from == null) {
            to.properties().forEach(e -> out.add(new SpecChange(Op.ADDED, "/" + escape(e.getKey()), null,
                    e.getValue())));
            return out;
        }
        walk("", from, to, out);
        return out;
    }

    public List<DiffLine> lines(String fromText, String toText) {
        List<String> left = split(fromText);
        List<String> right = split(toText);
        List<DiffLine> rows = new ArrayList<>();
        int l = 0;
        int r = 0;
        for (AbstractDelta<String> delta : DiffUtils.diff(left, right).getDeltas()) {
            int srcPos = delta.getSource().getPosition();
            while (l < srcPos) {
                rows.add(new DiffLine(Kind.SAME, l + 1, left.get(l), r + 1, right.get(r)));
                l++;
                r++;
            }
            List<String> removed = delta.getSource().getLines();
            List<String> added = delta.getTarget().getLines();
            int paired = Math.min(removed.size(), added.size());
            for (int i = 0; i < paired; i++) {
                rows.add(new DiffLine(Kind.CHANGED, l + 1, removed.get(i), r + 1, added.get(i)));
                l++;
                r++;
            }
            for (int i = paired; i < removed.size(); i++) {
                rows.add(new DiffLine(Kind.REMOVED, l + 1, removed.get(i), null, null));
                l++;
            }
            for (int i = paired; i < added.size(); i++) {
                rows.add(new DiffLine(Kind.ADDED, null, null, r + 1, added.get(i)));
                r++;
            }
        }
        while (l < left.size()) {
            rows.add(new DiffLine(Kind.SAME, l + 1, left.get(l), r + 1, right.get(r)));
            l++;
            r++;
        }
        return rows;
    }

    String toYaml(JsonNode tree) {
        return yaml.writeValueAsString(tree);
    }

    private static void walk(String pointer, JsonNode from, JsonNode to, List<SpecChange> out) {
        if (from.isObject() && to.isObject()) {
            TreeSet<String> keys = new TreeSet<>(from.propertyNames());
            keys.addAll(to.propertyNames());
            for (String key : keys) {
                child(pointer + "/" + escape(key), from.get(key), to.get(key), out);
            }
        } else if (from.isArray() && to.isArray()) {
            for (int i = 0; i < Math.max(from.size(), to.size()); i++) {
                child(pointer + "/" + i, from.get(i), to.get(i), out);
            }
        } else if (!from.equals(to)) {
            out.add(new SpecChange(Op.CHANGED, pointer, from, to));
        }
    }

    private static void child(String pointer, @Nullable JsonNode from, @Nullable JsonNode to, List<SpecChange> out) {
        if (from == null && to != null) {
            out.add(new SpecChange(Op.ADDED, pointer, null, to));
        } else if (from != null && to == null) {
            out.add(new SpecChange(Op.REMOVED, pointer, from, null));
        } else if (from != null) {
            walk(pointer, from, to, out);
        }
    }

    private static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private static List<String> split(String text) {
        return text.lines().toList();
    }
}
