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
- is restarted automatically if it crashes,
- ends together with the JVM, even when the JVM is killed and its shutdown hooks do not run.

The URL of each service never changes: the library itself keeps its port open for the whole session and forwards
connections to the Deno process, which gets a fresh internal port on every start. So no other program can take the port
in between, and while a service is down, requests fail right away instead of running into a timeout.

The library has no dependencies and targets Java 8.

## Usage

```java
YoutubeSourceServices services = YoutubeSourceServices.builder(gameDir.resolve("youtube-source-services"))
    .log(LOGGER::info)
    .build();

YoutubeSourceOptions options = new YoutubeSourceOptions()
    .setRemoteCipher(services.cipher().getUrl(), services.cipher().getToken(), "your-mod/1.0")
    .setRemotePoToken(services.poToken().getUrl(), services.poToken().getToken());

// The first start downloads Deno and the npm packages (about 200 MB on disk), so never wait for this on the render thread
services.start().whenComplete((ignored, error) -> {
    if (error != null) {
        LOGGER.error("Could not start the YouTube services", error);
    }
});
```

- URL and token are fixed per instance, so they can be passed to youtube-source before the services have started.
  They stay the same across restarts.
- `stop()` stops both services, including one that is still starting. `start()` can be called again afterwards.
- On JVM exit the services are stopped automatically.
- Either service can be disabled with `cipher(false)` or `poToken(false)` on the builder, for example when you use your
  own poToken server.

## Supported platforms

Every platform Deno publishes builds for: Windows, macOS and Linux (glibc), each on x64 and ARM64. A 32-bit JVM on
64-bit Windows works as well, since Deno runs in its own process.

Not supported: Android (Deno has no Android builds), 32-bit operating systems and musl-based Linux distributions.

## Updating

Versions, download URLs, repositories, the start timeout and the restart limit are all set in
[`services.properties`](src/main/resources/net/hycrafthd/youtube_source_services/services.properties).
Bump `yt-cipher.commit` and `ejs.commit` together: yt-cipher's Dockerfile names the ejs commit it expects. If a new
commit changes the log line a service prints once it is ready, update its `ready-marker` as well.

ejs's npm dependencies are pinned through an import map to the versions in its own `package.json`. Unversioned imports
in yt-cipher itself are pinned with `yt-cipher.pinned-imports`; check for new ones when bumping `yt-cipher.commit`.

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
