package com.kooritea.fcmfix.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The config system_server keeps on disk, one "key=value" per line. Package lists repeat their
 * key; options are true or false. A value that is not a boolean is passed on unchanged so that
 * ConfigSnapshot rejects the whole file and the previous snapshot stays in use.
 */
public final class ConfigFile {
    private static final String ALLOW_LIST = "allowList";
    private static final String EXCLUDE_LIST = "excludeList";

    private ConfigFile() {
    }

    /** The values in the form ConfigSnapshot reads, or null when there is no file yet. */
    public static Map<String, Object> read(File file) throws IOException {
        if (!file.isFile()) return null;
        return decode(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    /** Replaces the file in one step: a reader sees the old config or the new one, never half. */
    public static void write(File file, ConfigSnapshot snapshot) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        File temporary = new File(parent, file.getName() + ".tmp");
        Files.write(temporary.toPath(), encode(snapshot).getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static String encode(ConfigSnapshot snapshot) {
        StringBuilder text = new StringBuilder();
        for (String name : new TreeSet<>(snapshot.allowList)) text.append(ALLOW_LIST).append('=').append(name).append('\n');
        for (String name : new TreeSet<>(snapshot.excludeList)) text.append(EXCLUDE_LIST).append('=').append(name).append('\n');
        for (Map.Entry<String, Boolean> option : new java.util.TreeMap<>(snapshot.options).entrySet()) {
            text.append(option.getKey()).append('=').append(option.getValue()).append('\n');
        }
        return text.toString();
    }

    static Map<String, Object> decode(String text) {
        Map<String, Object> values = new HashMap<>();
        Set<String> allow = new HashSet<>();
        Set<String> exclude = new HashSet<>();
        for (String line : text.split("\n")) {
            int separator = line.indexOf('=');
            if (separator <= 0) continue;
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (ALLOW_LIST.equals(key)) allow.add(value);
            else if (EXCLUDE_LIST.equals(key)) exclude.add(value);
            else if ("true".equals(value)) values.put(key, Boolean.TRUE);
            else if ("false".equals(value)) values.put(key, Boolean.FALSE);
            else values.put(key, value);
        }
        values.put(ALLOW_LIST, allow);
        values.put(EXCLUDE_LIST, exclude);
        return values;
    }
}
