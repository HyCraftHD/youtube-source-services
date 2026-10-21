package net.hycrafthd.youtube_source_services;

import java.io.File;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Maps the current platform to the name Deno uses for its release archives.
 */
final class Platform {

    private Platform() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * @return the Deno target of this machine, e.g. {@code x86_64-pc-windows-msvc}
     * @throws UnsupportedOperationException if Deno has no build for this platform
     */
    static String denoTarget() {
        if (isAndroid(System::getenv, path -> new File(path).exists())) {
            throw new UnsupportedOperationException("Deno has no builds for Android");
        }
        return denoTarget(System.getProperty("os.name"), System.getProperty("os.arch"), System::getenv);
    }

    static String denoTarget(String osName, String osArch, Function<String, String> environment) {
        String os = osName.toLowerCase(Locale.ROOT);
        String cpu = cpu(osArch);

        if (os.contains("win")) {
            // A 32-bit JVM on 64-bit Windows reports x86, but Deno runs in its own process, so the OS architecture matters
            if (cpu == null) {
                cpu = cpu(environment.apply("PROCESSOR_ARCHITEW6432"));
            }
            return require(cpu, osName, osArch) + "-pc-windows-msvc";
        } else if (os.contains("mac") || os.contains("darwin")) {
            return require(cpu, osName, osArch) + "-apple-darwin";
        } else if (os.contains("linux")) {
            return require(cpu, osName, osArch) + "-unknown-linux-gnu";
        }
        throw unsupported(osName, osArch);
    }

    /**
     * Android launchers for Minecraft run a regular OpenJDK that reports os.name Linux, but Deno's Linux builds need
     * glibc, which Android does not have.
     */
    static boolean isAndroid(Function<String, String> environment, Predicate<String> fileExists) {
        return environment.apply("ANDROID_ROOT") != null
            || environment.apply("ANDROID_DATA") != null
            || fileExists.test("/system/build.prop");
    }

    private static String cpu(String arch) {
        if (arch == null) {
            return null;
        }

        switch (arch.toLowerCase(Locale.ROOT)) {
            case "amd64":
            case "x86_64":
            case "x64":
                return "x86_64";
            case "aarch64":
            case "arm64":
                return "aarch64";
            default:
                return null;
        }
    }

    private static String require(String cpu, String osName, String osArch) {
        if (cpu == null) {
            throw unsupported(osName, osArch);
        }
        return cpu;
    }

    private static UnsupportedOperationException unsupported(String osName, String osArch) {
        return new UnsupportedOperationException("Deno has no build for " + osName + " " + osArch);
    }
}
