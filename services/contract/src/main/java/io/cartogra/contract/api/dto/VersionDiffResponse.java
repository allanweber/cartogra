package io.cartogra.contract.api.dto;

import io.cartogra.contract.domain.DiffLine;
import io.cartogra.contract.domain.SpecChange;
import io.cartogra.contract.domain.VersionDiff;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;

public record VersionDiffResponse(
        @Nullable ContractVersionResponse from,
        ContractVersionResponse to,
        List<Change> changes,
        List<Line> lines
) {
    public record Change(String op, String pointer, List<String> path, @Nullable JsonNode before,
                         @Nullable JsonNode after) {
        static Change from(SpecChange c) {
            return new Change(c.op().name().toLowerCase(Locale.ROOT), c.pointer(), c.segments(), c.before(),
                    c.after());
        }
    }

    public record Line(String kind, @Nullable Integer leftNo, @Nullable String left,
                       @Nullable Integer rightNo, @Nullable String right) {
        static Line from(DiffLine l) {
            return new Line(l.kind().name().toLowerCase(Locale.ROOT), l.leftNo(), l.left(), l.rightNo(), l.right());
        }
    }

    public static VersionDiffResponse from(VersionDiff d) {
        return new VersionDiffResponse(
                d.from() == null ? null : ContractVersionResponse.from(d.from()),
                ContractVersionResponse.from(d.to()),
                d.changes().stream().map(Change::from).toList(),
                d.lines().stream().map(Line::from).toList());
    }
}
