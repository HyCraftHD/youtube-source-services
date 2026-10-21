# youtube-source-services

Runs the remote services that [youtube-source](https://github.com/lavalink-devs/youtube-source) can use for YouTube
playback as local processes, so end users do not have to install or host anything:

| Service | Purpose | youtube-source option |
|---|---|---|
| [yt-cipher](https://github.com/kikkia/yt-cipher) with [yt-dlp/ejs](https://github.com/yt-dlp/ejs) | Signature and `n` parameter deciphering | `setRemoteCipher(url, token, userAgent)` |
| [webpo-generator](https://github.com/ashton045/webpo-generator) | Proof of origin tokens (poToken) | `setRemotePoToken(url, token)` |

On first use the library downloads a pinned [Deno](https://deno.com) release (checksum verified) and the services at
pinned commits into a directory of your choice. Each service then runs as its own Deno process that:

- listens only on `127.0.0.1` and rejects requests without a random per-session token,
- runs in Deno's permission sandbox (read/write access only to its own directories),
- is restarted automatically on the same port if it crashes.

The library has no dependencies and targets Java 8.

## Usage

```java
DenoRuntime runtime = new DenoRuntime(gameDir.resolve("youtube-source-services"));
YtCipherService cipher = new YtCipherService(runtime, LOGGER::info);
WebPoService pot = new WebPoService(runtime, LOGGER::info);

YoutubeSourceOptions options = new YoutubeSourceOptions()
    .setRemoteCipher(cipher.getUrl(), cipher.getToken(), "your-mod/1.0")
    .setRemotePoToken(pot.getUrl(), pot.getToken());

// The first start downloads Deno and the npm packages (about 200 MB on disk), so never wait for this on the render thread
CompletableFuture.allOf(cipher.startAsync(), pot.startAsync());
```

URL and token are fixed per instance, so they can be passed to youtube-source before the services have started.
The processes are stopped with `close()` or automatically when the JVM exits.

## Supported platforms

Every platform Deno publishes builds for: Windows, macOS and Linux (glibc), each on x64 and ARM64. A 32-bit JVM on
64-bit Windows works as well, since Deno runs in its own process.

Not supported: Android (Deno has no Android builds), 32-bit operating systems and musl-based Linux distributions.

## Updating

All versions are pinned in
[`versions.properties`](src/main/resources/net/hycrafthd/youtube_source_services/versions.properties).
Bump `yt-cipher.commit` and `ejs.commit` together: yt-cipher's Dockerfile names the ejs commit it expects.

## Building and testing

```bash
./gradlew build             # compile and run the unit tests
./gradlew integrationTest   # download Deno and the services and run them on this machine
```

Integration tests share one download directory under `build/integration-services`. Set
`YOUTUBE_SOURCE_SERVICES_TEST_DIR` to move it, for example off a WSL-mounted Windows drive, where loading
webpo-generator's ~6,700 npm files is very slow.

## AI usage

The code in this repository was created with the help of AI (Claude by Anthropic) and reviewed and tested by the
maintainer.

## Licenses

The services are downloaded from their official repositories at runtime and are not part of this library.
Check their licenses before distributing software that uses them: yt-cipher currently has no license,
webpo-generator states GPL-3.0 in its `LICENSE` file and LGPL-3.0-or-later in its `package.json`.
yt-dlp/ejs is released under the Unlicense, Deno under the MIT license.
