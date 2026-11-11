package com.kooritea.fcmfix.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RunRecordTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void actionScriptReadsOneKeyValuePerLine() {
        String text = RunRecord.render("13.2.0", "abc1234", true, false, 1536, 1_760_000_000L,
                Arrays.asList("BroadcastFix", "AutoStartFix"),
                Arrays.asList("OplusProxyFix: NoSuchMethodError: first\nsecond line", "OplusStartupFix: boom"),
                Collections.singletonList("OplusDeviceIdleFix: not applicable"));
        assertEquals("version=13.2.0\ncommit=abc1234\nlate=true\nready=false\npid=1536\ntime=1760000000\n"
                + "active=2\nfailedCount=2\nskippedCount=1\n"
                + "failed=OplusProxyFix: NoSuchMethodError: first second line\n"
                + "failed=OplusStartupFix: boom\n", text);
    }

    @Test public void secondWriteReplacesTheFirstWithoutLeavingATemporaryFile() throws Exception {
        File file = new File(folder.getRoot(), "fcmfix/run.properties");
        RunRecord.write(file, "ready=false\n");
        RunRecord.write(file, "ready=true\n");
        assertEquals("ready=true\n", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(file.getParentFile(), "run.properties.tmp").exists());
    }
}
