package com.helmcompare.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmcompare.model.FolderCompare;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Stores folder comparisons under data/folder-compares/{id}/ (compare.json, info.json, left/, right/). */
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
                        infos.put(info.id, info);
                    } catch (IOException ignored) {
                        // skip unreadable entries
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void save(FolderCompare compare, Map<String, byte[]> left, Map<String, byte[]> right) {
        Path dir = root.resolve(compare.id);
        try {
            writeFiles(dir.resolve("left"), left);
            writeFiles(dir.resolve("right"), right);
            mapper.writeValue(dir.resolve("compare.json").toFile(), compare);
            FolderCompare.Info info = FolderCompare.Info.of(compare);
            mapper.writeValue(dir.resolve("info.json").toFile(), info);
            infos.put(compare.id, info);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeFiles(Path base, Map<String, byte[]> files) throws IOException {
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
            return Optional.of(mapper.readValue(root.resolve(id).resolve("compare.json").toFile(), FolderCompare.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** @param side "left" or "right" */
    public Optional<byte[]> file(String id, String side, String path) {
        Path base = root.resolve(id).resolve(side);
        Path target = base.resolve(path).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target)) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            return Optional.empty();
        }
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

    static void move(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
}
