package com.helmcompare.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Expands uploaded files (plain files, .zip, .tgz/.tar.gz, .tar) into a flat list of chart files. */
public final class ChartArchive {

    public record SourceFile(String path, byte[] content) {
        public String text() {
            return new String(content, StandardCharsets.UTF_8);
        }
    }

    private static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;

    private ChartArchive() {
    }

    public static List<SourceFile> expand(String name, byte[] content) {
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".zip")) return unzip(content);
            if (lower.endsWith(".tgz") || lower.endsWith(".tar.gz")) {
                return untar(new GZIPInputStream(new ByteArrayInputStream(content)));
            }
            if (lower.endsWith(".tar")) return untar(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read archive " + name + ": " + e.getMessage(), e);
        }
        String path = normalizePath(name);
        return path.isEmpty() ? List.of() : List.of(new SourceFile(path, content));
    }

    public static boolean isYaml(String path) {
        String l = path.toLowerCase(Locale.ROOT);
        return l.endsWith(".yaml") || l.endsWith(".yml");
    }

    /** Removes a single top-level folder shared by every file (e.g. "payments-api/Chart.yaml"). */
    public static List<SourceFile> stripCommonRoot(List<SourceFile> files) {
        if (files.isEmpty()) return files;
        String first = files.get(0).path();
        int slash = first.indexOf('/');
        if (slash < 0) return files;
        String root = first.substring(0, slash + 1);
        boolean shared = files.stream().allMatch(f -> f.path().startsWith(root) && f.path().length() > root.length());
        if (!shared) return files;
        return files.stream().map(f -> new SourceFile(f.path().substring(root.length()), f.content())).toList();
    }

    public static String normalizePath(String raw) {
        String p = raw.replace('\\', '/');
        String joined = Arrays.stream(p.split("/"))
                .filter(s -> !s.isEmpty() && !s.equals(".") && !s.equals(".."))
                .collect(Collectors.joining("/"));
        if (joined.startsWith("__MACOSX") || joined.endsWith(".DS_Store")) return "";
        return joined;
    }

    private static List<SourceFile> unzip(byte[] content) throws IOException {
        List<SourceFile> out = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String path = normalizePath(entry.getName());
                byte[] data = readLimited(zip);
                if (path.isEmpty()) continue;
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".tgz") || lower.endsWith(".tar.gz")) {
                    String prefix = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
                    for (SourceFile nested : expand(path, data)) out.add(new SourceFile(prefix + nested.path(), nested.content()));
                } else {
                    out.add(new SourceFile(path, data));
                }
            }
        }
        return out;
    }

    private static List<SourceFile> untar(InputStream in) throws IOException {
        List<SourceFile> out = new ArrayList<>();
        byte[] header = new byte[512];
        String longName = null;
        try (in) {
            while (in.readNBytes(header, 0, 512) == 512) {
                if (allZero(header)) break;
                String name = cString(header, 0, 100);
                String sizeField = cString(header, 124, 12).replaceAll("[^0-7]", "");
                long size = sizeField.isEmpty() ? 0 : Long.parseLong(sizeField, 8);
                if (size > MAX_ENTRY_BYTES) throw new IOException("Entry too large: " + name);
                char type = (char) header[156];
                if (cString(header, 257, 6).startsWith("ustar")) {
                    String prefix = cString(header, 345, 155);
                    if (!prefix.isEmpty()) name = prefix + "/" + name;
                }
                byte[] data = in.readNBytes((int) size);
                long padding = (512 - size % 512) % 512;
                in.skipNBytes(padding);

                if (type == 'L') {
                    longName = cString(data, 0, data.length);
                    continue;
                }
                if (type == 'x' || type == 'g') {
                    String pax = new String(data, StandardCharsets.UTF_8);
                    for (String rec : pax.split("\n")) {
                        int idx = rec.indexOf(" path=");
                        if (idx >= 0) longName = rec.substring(idx + 6);
                    }
                    continue;
                }
                if (longName != null) {
                    name = longName;
                    longName = null;
                }
                if (type == '0' || type == '\0') {
                    String path = normalizePath(name);
                    if (!path.isEmpty()) out.add(new SourceFile(path, data));
                }
            }
        }
        return out;
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        byte[] data = in.readNBytes((int) MAX_ENTRY_BYTES + 1);
        if (data.length > MAX_ENTRY_BYTES) throw new IOException("Archive entry too large");
        return data;
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8).trim();
    }
}
