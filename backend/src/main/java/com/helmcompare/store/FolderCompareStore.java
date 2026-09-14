package com.helmcompare.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmcompare.model.FolderCompare;
import com.helmcompare.model.FolderCompare.Node;
import com.helmcompare.model.FolderCompare.PairResult;
import com.helmcompare.model.SecretValues;
import com.helmcompare.service.FolderStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Stores folder comparisons under data/folder-compares/{id}/
 * (compare.json, info.json, side-0/, side-1/, side-2/, secret-values-side-N.json).
 * Comparisons saved before 3-way support use left/ and right/ (and secret-values-left|right.json); they are converted
 * to the current format when read.
 */
@Component
public class FolderCompareStore {

    private final ObjectMapper mapper;
    private final Path root;
    private final Map<String, FolderCompare.Info> infos = new ConcurrentHashMap<>();

    public FolderCompareStore(ObjectMapper mapper, @Value("${helmcompare.data-dir:data}") String dataDir) {
        this.mapper = mapper;
        this.root = Path.of(dataDir).toAbsolutePath().resolve("folder-compares");
        try {
            Files.createDirectories(root);
            try (Stream<Path> dirs = Files.list(root)) {
                dirs.map(d -> d.resolve("info.json")).filter(Files::exists).forEach(p -> {
                    try {
                        FolderCompare.Info info = mapper.readValue(p.toFile(), FolderCompare.Info.class);
                        if (info.sides == null || info.sides.isEmpty()) info = upgrade(p.getParent());
                        if (info != null) infos.put(info.id, info);
                    } catch (IOException ignored) {
                        // skip unreadable entries
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @param sides the files of every side, relative path → content */
    public void save(FolderCompare compare, List<Map<String, byte[]>> sides) {
        Path dir = root.resolve(compare.id);
        try {
            for (int i = 0; i < sides.size(); i++) writeFiles(dir.resolve("side-" + i), sides.get(i));
            write(dir, compare);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Path dir, FolderCompare compare) throws IOException {
        Files.createDirectories(dir);
        mapper.writeValue(dir.resolve("compare.json").toFile(), compare);
        FolderCompare.Info info = FolderCompare.Info.of(compare);
        mapper.writeValue(dir.resolve("info.json").toFile(), info);
        infos.put(compare.id, info);
    }

    private static void writeFiles(Path base, Map<String, byte[]> files) throws IOException {
        Files.createDirectories(base);
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            Path target = base.resolve(f.getKey()).normalize();
            if (!target.startsWith(base)) continue;
            Files.createDirectories(target.getParent());
            Files.write(target, f.getValue());
        }
    }

    public List<FolderCompare.Info> list() {
        return infos.values().stream().sorted(Comparator.comparing((FolderCompare.Info i) -> i.createdAt).reversed()).toList();
    }

    public Optional<FolderCompare> get(String id) {
        if (!infos.containsKey(id)) return Optional.empty();
        try {
            FolderCompare c = mapper.readValue(root.resolve(id).resolve("compare.json").toFile(), FolderCompare.class);
            if (c.sides == null || c.sides.isEmpty()) {
                convert(c);
                write(root.resolve(id), c);
            }
            return Optional.of(c);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<byte[]> file(String id, int side, String path) {
        Path base = sideDir(id, side);
        Path target = base.resolve(path).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target)) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<SecretValues> secretValues(String id, int side) {
        Path file = secretValuesFile(id, side);
        if (!infos.containsKey(id) || !Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(file.toFile(), SecretValues.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void saveSecretValues(String id, int side, SecretValues values) {
        try {
            mapper.writeValue(secretValuesFile(id, side).toFile(), values);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void deleteSecretValues(String id, int side) {
        try {
            Files.deleteIfExists(secretValuesFile(id, side));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean legacy(String id) {
        return Files.isDirectory(root.resolve(id).resolve("left"));
    }

    private Path sideDir(String id, int side) {
        checkSide(side);
        Path dir = root.resolve(id);
        if (legacy(id) && side < 2) return dir.resolve(side == 0 ? "left" : "right");
        return dir.resolve("side-" + side);
    }

    private Path secretValuesFile(String id, int side) {
        checkSide(side);
        Path dir = root.resolve(id);
        if (legacy(id) && side < 2) return dir.resolve("secret-values-" + (side == 0 ? "left" : "right") + ".json");
        return dir.resolve("secret-values-side-" + side + ".json");
    }

    private static void checkSide(int side) {
        if (side < 0 || side >= FolderCompare.MAX_SIDES) throw new IllegalArgumentException("Unknown side: " + side);
    }

    public void delete(String id) {
        infos.remove(id);
        Path dir = root.resolve(id);
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ───────────────────────────── conversion of the two-folder format ─────────────────────────────

    private FolderCompare.Info upgrade(Path dir) {
        try {
            FolderCompare c = mapper.readValue(dir.resolve("compare.json").toFile(), FolderCompare.class);
            if (c.sides == null || c.sides.isEmpty()) convert(c);
            write(dir, c);
            return FolderCompare.Info.of(c);
        } catch (IOException e) {
            return null;
        }
    }

    static void convert(FolderCompare c) {
        c.sides = new ArrayList<>(List.of(new FolderCompare.SideInfo(c.leftName, c.leftLabel),
                new FolderCompare.SideInfo(c.rightName, c.rightLabel)));
        c.leftName = c.rightName = c.leftLabel = c.rightLabel = null;
        if (c.root != null) convert(c.root);
        FolderStatus.summarize(c);
    }

    private static void convert(Node n) {
        String left = n.leftState == null ? "MISSING" : n.leftState;
        String right = n.rightState == null ? "MISSING" : n.rightState;
        n.states = new ArrayList<>(List.of(left, right));
        if (!n.dir) {
            n.sizes = new ArrayList<>(List.of(n.leftSize == null ? -1L : n.leftSize, n.rightSize == null ? -1L : n.rightSize));
            n.pairs = Node.newPairs();
            if (!"MISSING".equals(left) && !"MISSING".equals(right)) {
                n.pairs.put("0-1", new PairResult(n.status, n.reason, n.differences == null ? 0 : n.differences));
            }
        }
        n.status = n.leftState = n.rightState = n.reason = null;
        n.differences = n.identical = n.logicallySame = n.differs = n.leftOnly = n.rightOnly = null;
        n.leftSize = n.rightSize = null;
        if (n.children != null) n.children.forEach(FolderCompareStore::convert);
    }
}
