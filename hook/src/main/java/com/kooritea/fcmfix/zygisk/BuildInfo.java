package com.kooritea.fcmfix.zygisk;

/** The two values zygisk/build.sh fills in when it builds the hook dex. */
public final class BuildInfo {
    public static final String VERSION = "@VERSION@";
    public static final String COMMIT = "@COMMIT@";

    private BuildInfo() {
    }
}
