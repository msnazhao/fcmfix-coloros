package com.kooritea.fcmfix.util;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConfigFileTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static ConfigSnapshot snapshot() {
        Map<String, Object> values = new HashMap<>();
        values.put("allowList", new HashSet<>(Arrays.asList("b.app", "a.app")));
        values.put("excludeList", new HashSet<>(Collections.singleton("x.app")));
        values.put("autoAllowFcm", true);
        values.put("includeIceBoxDisableApp", false);
        return new ConfigSnapshot(values);
    }

    @Test public void writtenConfigReadsBackIdentically() throws Exception {
        File file = new File(folder.getRoot(), "fcmfix/config.properties");
        ConfigFile.write(file, snapshot());
        ConfigSnapshot back = new ConfigSnapshot(ConfigFile.read(file));
        assertEquals(snapshot().allowList, back.allowList);
        assertEquals(snapshot().excludeList, back.excludeList);
        assertEquals(snapshot().options, back.options);
        assertEquals("No temporary file is left beside the config", 1, file.getParentFile().list().length);
    }

    @Test public void rewriteReplacesTheOldConfig() throws Exception {
        File file = new File(folder.getRoot(), "config.properties");
        ConfigFile.write(file, snapshot());
        ConfigFile.write(file, new ConfigSnapshot(Collections.emptyMap()));
        ConfigSnapshot back = new ConfigSnapshot(ConfigFile.read(file));
        assertTrue(back.allowList.isEmpty());
        assertFalse(back.autoAllowFcm());
    }

    @Test public void missingFileIsNotAConfig() throws Exception {
        assertNull(ConfigFile.read(new File(folder.getRoot(), "none")));
    }

    @Test public void corruptOptionRejectsTheWholeFile() throws Exception {
        File file = folder.newFile("config.properties");
        Files.write(file.toPath(), "allowList=a\nautoAllowFcm=maybe\n".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> values = ConfigFile.read(file);
        assertThrows(IllegalArgumentException.class, () -> new ConfigSnapshot(values));
    }

    @Test public void packageNamesSurviveEvenWithAnEqualsSignInTheValue() {
        Map<String, Object> values = ConfigFile.decode("allowList=a.b\nallowList=c\ngarbage line\n=nokey\n");
        assertEquals(new HashSet<>(Arrays.asList("a.b", "c")), values.get("allowList"));
    }
}
