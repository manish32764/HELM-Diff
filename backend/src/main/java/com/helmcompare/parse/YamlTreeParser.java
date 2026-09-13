package com.helmcompare.parse;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses (possibly multi-document) YAML into {@link YNode} trees. Documents that fail to parse
 * because of leftover template syntax are retried with the offending lines blanked, so a single
 * odd line does not lose the whole document.
 */
public final class YamlTreeParser {

    /**
     * @param ignoredLines 1-based lines that had to be skipped to parse the file
     * @param failed       true when at least one document could not be parsed at all
     */
    public record Result(List<YNode> documents, List<String> warnings, List<Integer> ignoredLines, boolean failed) {
    }

    private static final int MAX_REPAIRS = 80;

    private YamlTreeParser() {
    }

    public static Result parse(String fileName, String text) {
        List<YNode> docs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Integer> ignored = new ArrayList<>();
        boolean[] failed = {false};
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int start = 0;
        for (int i = 0; i <= lines.length; i++) {
            if (i < lines.length && !isSeparator(lines[i])) continue;
            if (i > start) {
                parseDocument(fileName, Arrays.copyOfRange(lines, start, i), start, docs, warnings, ignored, failed);
            }
            start = i + 1;
        }
        return new Result(docs, warnings, ignored, failed[0]);
    }

    private static boolean isSeparator(String line) {
        if (line.startsWith("---")) return line.length() == 3 || Character.isWhitespace(line.charAt(3));
        return line.strip().equals("...");
    }

    private static void parseDocument(String file, String[] lines, int offset, List<YNode> docs, List<String> warnings,
                                      List<Integer> allIgnored, boolean[] failed) {
        boolean empty = Arrays.stream(lines).allMatch(l -> l.isBlank() || l.stripLeading().startsWith("#"));
        if (empty) return;
        List<Integer> ignored = new ArrayList<>();
        for (int attempt = 0; attempt < MAX_REPAIRS; attempt++) {
            try {
                Node node = yaml().compose(new StringReader(String.join("\n", lines)));
                if (node != null) docs.add(convert(node, offset, 0));
                if (!ignored.isEmpty()) {
                    warnings.add(file + ": ignored " + ignored.size() + " unparseable line(s) " + summarize(ignored));
                    allIgnored.addAll(ignored);
                }
                return;
            } catch (MarkedYAMLException e) {
                Mark mark = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
                int target = mark == null ? -1 : Math.min(mark.getLine(), lines.length - 1);
                while (target >= 0 && lines[target].isBlank()) target--;
                if (target < 0) break;
                lines[target] = "";
                ignored.add(offset + target + 1);
            } catch (RuntimeException e) {
                break;
            }
        }
        failed[0] = true;
        warnings.add(file + ": could not parse the document starting at line " + (offset + 1));
    }

    private static String summarize(List<Integer> lines) {
        List<Integer> sorted = lines.stream().sorted().toList();
        String shown = sorted.stream().limit(8).map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
        return "(line " + shown + (sorted.size() > 8 ? ", …" : "") + ")";
    }

    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        options.setMaxAliasesForCollections(500);
        options.setCodePointLimit(64 * 1024 * 1024);
        return new Yaml(options);
    }

    private static YNode convert(Node node, int offset, int depth) {
        int line = node.getStartMark().getLine() + offset + 1;
        if (depth > 150) {
            YNode n = YNode.nul(line);
            n.endLine = line;
            return n;
        }
        if (node instanceof AnchorNode anchor) return convert(anchor.getRealNode(), offset, depth + 1);
        if (node instanceof ScalarNode scalar) {
            YNode n = Tag.NULL.equals(scalar.getTag()) ? YNode.nul(line) : YNode.scalar(line, scalar.getValue());
            Mark end = scalar.getEndMark();
            n.endLine = end == null ? line : Math.max(line, end.getLine() + offset + (end.getColumn() == 0 ? 0 : 1));
            return n;
        }
        if (node instanceof SequenceNode sequence) {
            YNode out = YNode.seq(line);
            out.endLine = line;
            for (Node child : sequence.getValue()) {
                YNode c = convert(child, offset, depth + 1);
                out.seq.add(c);
                out.endLine = Math.max(out.endLine, c.endLine);
            }
            return out;
        }
        if (node instanceof MappingNode mapping) {
            YNode out = YNode.map(line);
            out.endLine = line;
            for (NodeTuple tuple : mapping.getValue()) {
                Node keyNode = tuple.getKeyNode();
                String key = keyNode instanceof ScalarNode ks ? ks.getValue() : "?";
                YNode value = convert(tuple.getValueNode(), offset, depth + 1);
                int keyLine = keyNode.getStartMark().getLine() + offset + 1;
                out.endLine = Math.max(out.endLine, Math.max(keyLine, value.endLine));
                if ("<<".equals(key) && value.isMap()) {
                    value.map.forEach((k, v) -> {
                        if (!out.has(k)) out.put(k, value.keyLine(k), v);
                    });
                    continue;
                }
                out.put(key, keyLine, value);
            }
            return out;
        }
        YNode n = YNode.nul(line);
        n.endLine = line;
        return n;
    }
}
