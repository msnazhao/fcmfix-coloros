package com.kooritea.fcmfix.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * What system_server leaves on disk once its hooks are installed, for the root manager's Action
 * button (action.sh): one "key=value" per line, failures one line each. logcat on ColorOS keeps
 * only minutes of history, so the log cannot answer "did the module load this boot". The pid
 * lets the script tell this boot's record from a stale one.
 */
public final class RunRecord {
    private RunRecord() {
    }

    public static String render(String version, String commit, boolean late, boolean ready, int pid,
                                long epochSeconds, List<String> active, List<String> failed,
                                List<String> skipped) {
        StringBuilder text = new StringBuilder();
        line(text, "version", version);
        line(text, "commit", commit);
        line(text, "late", String.valueOf(late));
        line(text, "ready", String.valueOf(ready));
        line(text, "pid", String.valueOf(pid));
        line(text, "time", String.valueOf(epochSeconds));
        line(text, "active", String.valueOf(active.size()));
        line(text, "failedCount", String.valueOf(failed.size()));
        line(text, "skippedCount", String.valueOf(skipped.size()));
        for (String entry : failed) line(text, "failed", entry);
        return text.toString();
    }

    /** Replaces the file in one step: the script reads the old record or the new one, never half. */
    public static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        File temporary = new File(parent, file.getName() + ".tmp");
        Files.write(temporary.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** A reason can carry a newline; the reader takes one line per entry. */
    private static void line(StringBuilder text, String key, String value) {
        text.append(key).append('=').append(String.valueOf(value).replace('\n', ' ').replace('\r', ' ')).append('\n');
    }
}
