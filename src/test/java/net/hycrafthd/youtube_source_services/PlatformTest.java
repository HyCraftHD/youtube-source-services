package net.hycrafthd.youtube_source_services;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformTest {

    private static final Function<String, String> NO_ENV = key -> null;

    @Test
    void windows() {
        assertEquals("x86_64-pc-windows-msvc", Platform.denoTarget("Windows 10", "amd64", NO_ENV));
        assertEquals("aarch64-pc-windows-msvc", Platform.denoTarget("Windows 11", "aarch64", NO_ENV));
    }

    @Test
    void windows32BitJvmOn64BitWindowsUsesOsArchitecture() {
        assertEquals("x86_64-pc-windows-msvc", Platform.denoTarget("Windows 10", "x86", env("PROCESSOR_ARCHITEW6432", "AMD64")));
        assertEquals("aarch64-pc-windows-msvc", Platform.denoTarget("Windows 11", "x86", env("PROCESSOR_ARCHITEW6432", "ARM64")));
    }

    @Test
    void windows32BitOsIsUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> Platform.denoTarget("Windows 7", "x86", NO_ENV));
    }

    @Test
    void macos() {
        assertEquals("x86_64-apple-darwin", Platform.denoTarget("Mac OS X", "x86_64", NO_ENV));
        assertEquals("aarch64-apple-darwin", Platform.denoTarget("Mac OS X", "aarch64", NO_ENV));
    }

    @Test
    void linux() {
        assertEquals("x86_64-unknown-linux-gnu", Platform.denoTarget("Linux", "amd64", NO_ENV));
        assertEquals("aarch64-unknown-linux-gnu", Platform.denoTarget("Linux", "aarch64", NO_ENV));
    }

    @Test
    void unsupportedPlatforms() {
        assertThrows(UnsupportedOperationException.class, () -> Platform.denoTarget("Linux", "arm", NO_ENV));
        assertThrows(UnsupportedOperationException.class, () -> Platform.denoTarget("Linux", "i386", NO_ENV));
        assertThrows(UnsupportedOperationException.class, () -> Platform.denoTarget("FreeBSD", "amd64", NO_ENV));
    }

    @Test
    void android() {
        assertTrue(Platform.isAndroid(env("ANDROID_ROOT", "/system"), path -> false));
        assertTrue(Platform.isAndroid(env("ANDROID_DATA", "/data"), path -> false));
        assertTrue(Platform.isAndroid(NO_ENV, "/system/build.prop"::equals));
        assertFalse(Platform.isAndroid(NO_ENV, path -> false));
    }

    @Test
    void currentPlatformIsSupported() {
        // The CI matrix only contains platforms Deno has builds for
        Platform.denoTarget();
    }

    private static Function<String, String> env(String key, String value) {
        Map<String, String> environment = Collections.singletonMap(key, value);
        return environment::get;
    }
}
