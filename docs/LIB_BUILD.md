# Adopted release: v0.3.7

Current consumer pin: `7cbad5d8198d37e9ded767d33ac03f2cf15875f4`, ABI 3.
Release AAR SHA-256: `15d60510f170c269631eedb182ad2cfed991e4d33ce19aa3c7ea364296594843`.
Use `scripts/fetch-or-build-lib.sh --release --host-jni` with a checkout at this
source commit. Regenerate `strings_ot3.xml` with `tools/import-ot3-strings`.
The older version examples below describe the build pipeline's history; the lock
is authoritative. New consumer contracts are documented in the lib's
`docs/ANDROID_PARITY.md` at the pinned source.

# Building the lib-t3-rust AAR

ot3-Android consumes [lib-t3-rust](https://github.com/avillagran/lib-t3-rust) as an
Android archive with the canonical Kotlin adapter (`dev.avillagran.t3.T3Client`), the
generated typed wrappers (`T3Api`) and JNI libraries for arm64-v8a, armeabi-v7a, x86
and x86_64. `scripts/fetch-or-build-lib.sh` downloads the archive from a GitHub release
(or builds it from a lib checkout) and places it under a stable name:

```
libs/lib-t3-rust.lock                    committed: the release tag and AAR SHA-256 this repository targets
libs/lib-t3-rust-android.aar             the AAR, referenced as files("libs/lib-t3-rust-android.aar")
libs/lib-t3-rust-android.aar.sha256      sha256sum -c compatible checksum
libs/lib-t3-rust-android.properties      version, source commit, tag, describe, abiVersion, origin, sha256
libs/host/linux-x86_64/libt3_jni.so      desktop JNI library for JVM unit tests
libs/host/linux-x86_64/lib-t3-rust.properties
```

**Invariant: `libs/lib-t3-rust.lock` is the only committed file under `libs/`.** It is
the pin. The AAR, its checksum, its properties and the host library are build outputs
and are listed in `.gitignore`. Regenerate them; never add them to a commit.

## Current target: v0.3.4 from the GitHub release, ABI 3

The app targets the **lib-t3-rust v0.3.4** release (commit `e2ec546`), C ABI
`T3_PROTOCOL_ABI_VERSION 3`, unchanged since v0.3.0. The AAR is the release asset
`lib-t3-rust-android-v0.3.4.aar`, pinned by `libs/lib-t3-rust.lock`:

```
tag=v0.3.4
sha256=b90064ae893cf0239a3d19b3bf213ebb8c92d6e14da0a8d4a4ff9ae083bfc036
```

```sh
scripts/fetch-or-build-lib.sh --release --host-jni
```

downloads and verifies that AAR (see "Release" below) and builds the host JNI library
from a lib checkout at the lock's tag (`git -C "$LIB_T3_RUST_DIR" checkout v0.3.4`):
the release does not ship one (LIB_REQUESTS.md, finding 7). Without a checkout,
`--release --no-host-jni` installs the AAR alone. Moving the target means updating the
lock (tag and SHA-256) in its own change.

ABI 3 is additive over ABI 2:

- typed API: ops `call` (`T3Client.call(fn, args, timeoutMs)`) and `describe`
  (`T3Client.describe()`, offline);
- streams: `wakeSubscriptions` (`T3Client.wakeSubscriptions()`,
  `T3Subscription.wake()`), and `pollSubscription` adds `boundaries`, `idle`,
  `completionMarker` and `errorMessage` (`T3Batch` fields; `T3Chunks` regroups items
  into server chunks);
- transport: `requestOk`, `fetchDescriptor`, `pairBearer`, `boundedThread`,
  `connectBearer`/`connectRelay` `options`; binary `assetBytes` and `projectFavicon`
  through the `image` native (`t3_client_image`);
- errors carry `message` and `requiredScope` (`T3Exception.reason`,
  `T3Exception.requiredScope`).

v0.3.1 completed the typed API on the same ABI: the registry covers 99 unary functions
in 20 modules, 13 typed streams and 4 composed functions (116 `describe` entries);
`subscribe` accepts a registry `fn` with `args` instead of a raw `tag`; `describe`
lists `streams` (item kinds, start kinds, cursor field); `pollSubscription` returns the
resume `cursor` and the `start` kind (`snapshot` or `resume`); and the Kotlin adapter
adds `T3Client.subscribeFn` and the `ThreadStream`/`ThreadUpdate` facade for the
thread and shell streams.

v0.3.2 changes only the Android bindings, tools and workflows (`src/` and `include/`
are identical to v0.3.1): `T3Api` ships in the AAR; `T3Client.assetBytes(resource,
maxBytes, timeoutMs)` and `projectFavicon(cwd, path?)` return `T3Asset(status,
bytes?)`; `connectBearer(..., options)` and `connectRelay(..., clientId = "t3-mobile",
options)`; JNI failures with a stable code arrive as `T3Exception(code)` (not yet
`image()`'s `missing_image_bytes`); the
`StreamFixture` compile error is fixed; and every `v*` tag publishes the AAR with its
SHA-256 as a GitHub release.

v0.3.3 adds the op `domain` on the same ABI (`src/domain/mod.rs`, `src/ffi_call.rs`):
37 pure functions with no connection and no network (`T3Client.domain(fn, args)`,
`T3Api(client).domain.<module>.<fn>(…)`), listed under `describe().domain` with their
args and a one-line result shape. A `domain` request may be up to 72 MiB (a bounded
thread snapshot); other operations keep 8 MiB. The app uses `projection.apply`,
`timeline.view` and `sidebar.view` (with its `projectIdentities`, the output of
`projects.identity`) through `DomainEngine` (`core/.../transport/DomainEngine.kt`).

v0.3.4 keeps the ABI, the operations, `src/stream.rs`, the API registry and
`T3Client.kt` as they were, and adds 71 `domain` functions (108 in 29 modules):
`diffReview`, `usageSummary`, `quotaSources`, the rest of `pullRequests`, `branchPicker`,
`toolInspector`, `planWorkflow`, `gitControls`, `nativeSettings`, `scopedSettings`,
`checkpointRevert`, `timeline.minimap` / `minimapIndex`, `subagents`, `theme.tokens` (an
Omarchy `colors.toml` in, ot3's colour roles out) and `i18n.t` / `catalog` / `resolve` /
`languages` over ot3's string catalog (20 languages, 1 796 English sources). The AAR now
also carries that catalog as the asset `assets/lib-t3-rust/i18n.json` (2 837 911 bytes,
the lib's `data/i18n.json`; `tools/package-android`). Gradle merges an AAR's `assets/`
into the consuming app's assets, so in the APK it is `assets/lib-t3-rust/i18n.json`,
read at run time with `context.assets.open("lib-t3-rust/i18n.json")`; a build-time
generator reads the same entry from `libs/lib-t3-rust-android.aar` directly.

`capabilities` on v0.3.4 reports `abiVersion 3`, `libraryVersion 0.3.4`, `apiVersion
1`, `functions 116`, `domainFunctions 108` and 19 `operations`: the 16 base operations
plus `call`, `describe` and `domain` (`src/ffi.rs` at the tag); `describe` lists 116
`functions`, 13 `streams` and 108 `domain` entries (all pinned in `NativeSmokeTest`).
The lib's own references: `README.md` ("Omi / Kotlin", "Typed API", "Domain
operations"), `docs/ANDROID.md` (Domain operations, Strings) and
`include/t3_protocol.h`. What the app still needs from the lib, and the v0.3.4 status of
each earlier finding, is tracked in [LIB_REQUESTS.md](LIB_REQUESTS.md).

## Building from source (unreleased work)

```sh
scripts/fetch-or-build-lib.sh
```

The script's default mode, for lib work that has no release yet; the target itself
comes from the release. It builds the current `HEAD` of a lib-t3-rust checkout,
located in this order: `$LIB_T3_RUST_DIR`, `$HOME/Work/lib-t3-rust`, `../lib-t3-rust`
next to this repository. A `LIB_T3_RUST_DIR` that is set but is not a lib-t3-rust
checkout is an error; the other locations are only tried when it is unset. In that
checkout the script writes only the untracked `target/` and `dist/`; no git mutations.
Run the script again after moving the checkout. A locally packaged AAR is not
byte-identical to the release one, so a source build never satisfies the lock; switch
back with `scripts/fetch-or-build-lib.sh --release`.

Steps, all run inside the lib checkout with its own tools:

1. `tools/build-android arm64-v8a x86_64 armeabi-v7a x86` cross-compiles the Rust
   core and the C++ JNI adapter for every ABI with the NDK (API 24, 16 KiB page
   alignment). Outputs go to the lib's `target/` and `dist/android/<abi>/`, each
   with a `COMMIT` file.
2. `tools/package-android` refuses any ABI whose `COMMIT` is not `HEAD`, compiles
   `T3Client.kt` against `android.jar` with the Kotlin compiler (K2JVMCompiler) and
   assembles `dist/lib-t3-rust-android-v<version>.aar` plus its checksum.
3. The AAR is checked for `classes.jar`, the four `jni/<abi>/libt3_jni.so`,
   `AndroidManifest.xml`, `proguard.txt` and `META-INF/lib-t3-rust.properties`, its
   embedded `source=` commit is compared with the checkout's `HEAD`, and it is copied
   to `libs/lib-t3-rust-android.aar`.

`package-android` refuses a checkout with uncommitted changes, so the script stops
early in that case: every AAR is traceable to one lib commit.

`libs/lib-t3-rust-android.properties` records the provenance:

```
version=0.3.2
source=26e67a7ce253a4cd84d91ca15071ecf578baf44a
tag=v0.3.2
describe=v0.3.2
abiVersion=3
origin=source
repository=https://github.com/avillagran/lib-t3-rust
sha256=...
installed=...
```

`tag` is the exact tag at `HEAD` (`none` between tags), `describe` is `git describe
--tags --always` (for example `v0.3.2-2-g1234abc` past the tag) and `abiVersion` is
`T3_PROTOCOL_ABI_VERSION` from `include/t3_protocol.h`. The script warns when the tag
and the Cargo version disagree.

A first build of the four ABIs takes 10 to 25 minutes; with a warm Cargo cache it
takes well under a minute. The script is idempotent: its key is the lib commit **and**
`git describe --tags --always`. If the properties already record both and the checksum
still matches, it does nothing; a tag added to an already built commit triggers a
reinstall so `tag`/`describe` stay true. If the lib's `dist/` already holds an intact
AAR packaged from that commit, it is reused without rebuilding. `--force` always
rebuilds. A lock on `libs/.fetch-or-build.lock` (`flock`) stops a second run in the
same repository while one is in progress.

## Options

| Option | Effect |
|---|---|
| (none) | Build the AAR from source, then the host JNI library on x86_64 Linux. |
| `--release [<tag>]` | Download the AAR from a GitHub release instead of building it; without a tag, the one in `libs/lib-t3-rust.lock`. |
| `--min-abi <n>` | With `--release`, refuse an AAR whose ABI is below `<n>` or unknown (default `T3_MIN_ABI`, else 3). |
| `--sha256 <hex>` | With `--release`, also require this SHA-256 for the AAR. |
| `--host-jni` | Build the host JNI library even when it is not built by default (e.g. with `--release`). |
| `--host-jni-only` | Build only the host JNI library; leave the AAR alone. |
| `--no-host-jni` | Skip the host JNI library. |
| `--lib-fixtures` | Also run the lib's host fixtures (`tools/test-jni`, `tools/test-kotlin-jni`); see below. |
| `--force` | Ignore what `libs/` (and the lib's `dist/`) already holds. |

`--host-jni-only` cannot be combined with `--release` (it builds no AAR), nor
`--no-host-jni` with `--host-jni`/`--host-jni-only`; `--sha256` needs `--release`.

Exit status: 0 on success, 1 on any failure (a missing tool, a refused AAR, a failed
build or a failed fixture), 2 on a usage error (unknown option, missing or malformed
argument, contradictory flags).

## Environment

Every variable has a default for the development machine; set it to override.

| Variable | Default | Used for |
|---|---|---|
| `LIB_T3_RUST_DIR` | `$HOME/Work/lib-t3-rust`, then `../lib-t3-rust` | lib checkout |
| `LIB_T3_RUST_REPO` | `avillagran/lib-t3-rust` | release downloads |
| `T3_MIN_ABI` | `3` | ABI floor for `--release` (`--min-abi` overrides) |
| `JAVA_HOME` | `$HOME/.local/jdk` | `java`, `jar`, JNI headers; only checked when building |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | `$HOME/.android-sdk` | SDK root |
| `ANDROID_NDK_HOME` | `$ANDROID_HOME/ndk/28.2.13676358` | cross-compilation |
| `ANDROID_PLATFORM` | `android-36` | selects `android.jar` |
| `ANDROID_JAR` | `$ANDROID_HOME/platforms/$ANDROID_PLATFORM/android.jar` | Kotlin adapter compile |
| `KOTLIN_VERSION` | `2.3.21` | compiler and stdlib looked up in the Gradle cache |
| `KOTLIN_STDLIB` | `kotlin-stdlib-$KOTLIN_VERSION.jar` from the Gradle cache | adapter compile |
| `KOTLIN_COMPILER_CLASSPATH` | assembled from the Gradle cache (below) | adapter compile |
| `JSON_JAR` | newest `org.json:json` jar in the Gradle cache or `~/.m2` | host fixtures |
| `LIB_FIXTURE_TIMEOUT` | `900` (seconds per fixture tool) | host fixtures |
| `GRADLE_USER_HOME` | `$HOME/.gradle` | cache location |
| `CARGO_BUILD_JOBS` | `nproc`, at most 8 | Rust and CMake parallelism |
| `CARGO_TARGET_DIR` | the lib's `target/` | Cargo output |

When `KOTLIN_COMPILER_CLASSPATH` is not set, the script assembles it from
`$GRADLE_USER_HOME/caches/modules-2/files-2.1` using the runtime dependencies that
`kotlin-compiler-embeddable` declares: `kotlin-compiler-embeddable`, `kotlin-stdlib`,
`kotlin-script-runtime`, `kotlin-daemon-embeddable` (all `$KOTLIN_VERSION`),
`kotlin-reflect` 1.6.10, `kotlinx-coroutines-core-jvm` 1.8.0 and
`org.jetbrains:annotations` 13.0 (`KOTLIN_REFLECT_VERSION`,
`KOTLINX_COROUTINES_VERSION` and `JETBRAINS_ANNOTATIONS_VERSION` override those),
plus `trove4j` when cached. If any is missing, it lists the missing coordinates and
stops; any Gradle build that uses that Kotlin version populates the cache. When a
cache directory holds the same jar under several hashes, the first path in sort order
is used. `ANDROID_JAR`, `KOTLIN_STDLIB`, every `KOTLIN_COMPILER_CLASSPATH` entry and
`JSON_JAR` are made absolute before the lib's tools run from its checkout. Nothing is
downloaded and no SDK, NDK or Rust target is installed by the script. With Kotlin
2.3.21 the v0.3.0 `T3Client.kt` compiled against `android.jar` without warnings.

## Release (the target)

```sh
scripts/fetch-or-build-lib.sh --release             # tag and SHA-256 from libs/lib-t3-rust.lock
scripts/fetch-or-build-lib.sh --release --host-jni  # plus the host library from a checkout at that tag
```

Downloads `lib-t3-rust-android-v<ver>.aar` and its `.sha256` from the GitHub release
(with `gh` when it is authenticated, otherwise `curl` restricted to HTTPS), verifies
the checksum and the AAR layout (`unzip -Z1`), and installs it under the same stable
name. The properties file records `origin=release <tag>`, the commit embedded in the
AAR, `tag`/`describe` as the release tag and `abiVersion` read from
`include/t3_protocol.h` at that tag. A release that does not exist (HTTP 404) is
reported as not published. For v0.3.4:

```
version=0.3.4
source=e2ec5467389adf0885623480d7e2006a7ae3d019
tag=v0.3.4
describe=v0.3.4
abiVersion=3
origin=release v0.3.4
repository=https://github.com/avillagran/lib-t3-rust
sha256=b90064ae893cf0239a3d19b3bf213ebb8c92d6e14da0a8d4a4ff9ae083bfc036
installed=...
```

Without `--host-jni` this path needs no JDK, Cargo, CMake or lib checkout: only `gh`
or `curl`, `unzip` and `sha256sum`. `JAVA_HOME` is not checked unless something is built.

**ABI floor.** The AAR is refused when its ABI is below `--min-abi` (default
`T3_MIN_ABI`, else 3) or cannot be read. v0.2.0, the latest release before v0.3.2, is
ABI 2 and lacks everything listed under "Current target"; it only installs with an
explicit `--min-abi 2`. The floor is also applied when an already installed release
AAR would be kept.

**Pinning.** `--sha256 <hex>` requires an exact AAR digest on top of the release's own
`.sha256`. The committed `libs/lib-t3-rust.lock` (`tag=`, `sha256=` and the AAR's `source=` commit, shown
under "Current target") pins the release path for everyone.

When it exists, `--release` without a tag uses its `tag`, a different tag is refused,
and the downloaded AAR must match its `sha256` (and `--sha256`, if given, must agree
with it). Source builds ignore it, apart from a warning when the checkout is at
another tag; a locally packaged AAR is not byte-identical to the release one. To move
the target, take the new release's SHA-256 (`gh release download <tag> -R
avillagran/lib-t3-rust -p '*.aar*'`, then `sha256sum -c` and `sha256sum`; GitHub also
shows each asset's digest) and update both lines of the lock in one commit.

**Host library with a release.** `--release <tag> --host-jni` builds the host JNI
library from the lib checkout, so the checkout must be at the commit the AAR was
packaged from (its `source=`); otherwise the script stops before installing anything
and prints both commits. Releases, v0.3.4 included, do not publish a host library
(LIB_REQUESTS.md, finding 7), so a lib checkout at the lock's tag stays necessary for it.

## CI recipe

`.github/workflows/android.yml` gets the lib before the Gradle step: it reads `tag=`
from `libs/lib-t3-rust.lock`, checks out `avillagran/lib-t3-rust` at that `source=` commit into
`lib-t3-rust/` (the repository is public, so the default token reads it), restores
`libs/` and the Cargo registry from a cache keyed on the lock's hash, and runs:

```yaml
- run: scripts/fetch-or-build-lib.sh --release --host-jni
  env:
    LIB_T3_RUST_DIR: ${{ github.workspace }}/lib-t3-rust
    GH_TOKEN: ${{ github.token }}
```

`ubuntu-latest` provides Rust, CMake and (through `actions/setup-java`) the JDK the host
build needs; no Android NDK or Rust Android target is used. With a cache hit the script
finds the AAR and the host library already installed for the lock and does nothing.
The second checkout lands inside the workspace, so `LIB_T3_RUST_DIR` must be set; the
default `../lib-t3-rust` is not used.

A job that only needs the AAR can skip the lib checkout and the toolchain:

```yaml
- run: scripts/fetch-or-build-lib.sh --release --no-host-jni   # tag and sha256 from libs/lib-t3-rust.lock
```

In a shell step that pipes the script's output (`| tail`, `| tee`), use
`set -o pipefail` so its exit status is not lost.

## Host JNI library for JVM tests

`T3Client` loads `libt3_jni` through `System.loadLibrary`. Android libraries do not
load on a desktop JVM, so on x86_64 Linux the script also builds a desktop
`libs/host/linux-x86_64/libt3_jni.so` the same way as the lib's `tools/test-jni`:
`cargo build --release --locked` for the host, then CMake of `bindings/android/cpp`
against the host JDK's `jni.h` (found through `JAVA_HOME`) into the lib's
`target/jni-host`, from where it is copied. It is skipped when its properties already
record the same commit and describe and its checksum matches.

JVM unit tests then run against the real Rust core without an emulator, given:

- `-Djava.library.path=libs/host/linux-x86_64`;
- an org.json implementation on the test classpath, because `android.jar` only has
  stubs (for example `org.json:json`, as Robolectric-free unit tests need anyway);
- the Kotlin adapter classes, from the AAR's `classes.jar`.

A manual smoke against the installed artifacts, in a scratch directory:

```sh
work=$(mktemp -d)
cat > "$work/Smoke.kt" <<'EOF'
import dev.avillagran.t3.T3Client
import org.json.JSONObject

fun main() = T3Client().use { client ->
    val caps = client.execute(JSONObject().put("op", "capabilities")) as JSONObject
    val api = client.describe()
    println("abi=${caps.getInt("abiVersion")} lib=${caps.getString("libraryVersion")} " +
        "ops=${caps.getJSONArray("operations").length()} functions=${caps.getInt("functions")} " +
        "describe=${api.getJSONArray("functions").length()} streams=${api.getJSONArray("streams").length()}")
}
EOF
unzip -o -q libs/lib-t3-rust-android.aar classes.jar -d "$work"
java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -classpath "$work/classes.jar:$JSON_JAR:$KOTLIN_STDLIB" \
  -d "$work/out" "$work/Smoke.kt"
java -Djava.library.path=libs/host/linux-x86_64 \
  -cp "$work/out:$work/classes.jar:$JSON_JAR:$KOTLIN_STDLIB" SmokeKt
rm -rf "$work"
# v0.3.1: abi=3 lib=0.3.1 ops=18 functions=116 describe=116 streams=13
# v0.3.2: abi=3 lib=0.3.2 ops=18 functions=116 describe=116 streams=13
# v0.3.3: abi=3 lib=0.3.3 ops=19 functions=116 describe=116 streams=13
# v0.3.4: abi=3 lib=0.3.4 ops=19 functions=116 describe=116 streams=13
```

## The lib's host fixtures

lib-t3-rust ships host fixtures for its Android bindings under
`bindings/android/tests/dev/avillagran/t3/`: `T3Native.java` (Java, raw JNI),
`KotlinFixture.kt` (wrapper, capabilities, typed API, Unicode, stable errors,
concurrent close), `StreamFixture.kt` (chunk boundaries, completion marker, idle, wake
probe, error reasons against a fake WebSocket). Up to v0.3.1 that directory also held
`T3Api.kt` (generated typed wrappers); since v0.3.2 it is generated next to
`T3Client.kt` in `bindings/android/kotlin/dev/avillagran/t3/` and shipped in the AAR.
They run with the lib's own tools, which only write under its `target/`:

```sh
scripts/fetch-or-build-lib.sh --host-jni-only --lib-fixtures
```

This resolves the Kotlin classpath and an org.json jar as above, then runs, inside the
lib checkout, `tools/test-jni` (rebuilds `target/jni-host/libt3_jni.so`, runs
`T3Native`) and `tools/test-kotlin-jni` (compiles `T3Client.kt` and `T3Api.kt` with the
Kotlin fixtures against org.json, runs `KotlinFixtureKt` and `StreamFixture`). Each tool
is reported separately; if either fails, the script prints `lib fixtures failed: ...`
and exits 1. By hand:

```sh
cd "$LIB_T3_RUST_DIR"
JAVA_HOME=... KOTLIN_STDLIB=... KOTLIN_COMPILER_CLASSPATH=... JSON_JAR=... tools/test-jni
JAVA_HOME=... KOTLIN_STDLIB=... KOTLIN_COMPILER_CLASSPATH=... JSON_JAR=... tools/test-kotlin-jni
```

Status at v0.3.0 (2026-10-24): `tools/test-jni` passes. `tools/test-kotlin-jni` does
not compile (`StreamFixture.kt:150`); with that one line corrected in a scratch copy,
`KotlinFixture` and `StreamFixture` pass with org.json 20180813 and 20250517. Details
and the proposed fix are in [LIB_REQUESTS.md](LIB_REQUESTS.md). The AAR is not
affected: it only contains `T3Client.kt`.

Status at v0.3.1 (2026-10-24): unchanged. `tools/test-jni` passes (`JNI_PASS`),
`tools/test-kotlin-jni` still fails to compile at `StreamFixture.kt:150`, so
`--lib-fixtures` exits 1 against a v0.3.1 checkout. That is expected until the target
moves to v0.3.2, where both tools pass (`KOTLIN_JNI_PASS`, `KOTLIN_STREAMS_PASS`).

Status at v0.3.2 (2026-10-24): with the checkout at the tag and the
release AAR installed, `scripts/fetch-or-build-lib.sh --lib-fixtures` prints
`JNI_PASS`, `KOTLIN_JNI_PASS` and `KOTLIN_STREAMS_PASS` and exits 0; it rebuilds
nothing in `libs/`, since the installed AAR already records the checkout's commit
and describe.

Status at v0.3.3 (2026-10-25): with the checkout at the tag and the
release AAR installed, `scripts/fetch-or-build-lib.sh --host-jni-only --lib-fixtures`
prints `JNI_PASS`, `KOTLIN_JNI_PASS` (now including the domain functions) and
`KOTLIN_STREAMS_PASS` and exits 0.

## Checking an AAR

```sh
cd libs && sha256sum -c lib-t3-rust-android.aar.sha256
unzip -l lib-t3-rust-android.aar
unzip -p lib-t3-rust-android.aar META-INF/lib-t3-rust.properties
javap -cp classes.jar dev.avillagran.t3.T3Client   # after unzipping classes.jar
```

Each `jni/<abi>/libt3_jni.so` must be an ELF for its ABI with every `LOAD` segment
aligned to 0x4000 (16 KiB pages), as `readelf -lW` shows (true for all four in the
v0.3.2 release AAR). On v0.3.1, `T3Client` exposes `call`, `describe`, `subscribeFn`
and `wakeSubscriptions`, and `classes.jar` holds ten classes (`T3Client`, `T3ClientKt`, `T3Exception`, `T3Batch`, `T3Chunks`,
`T3Subscription`, `T3Native`, `ThreadStream`, `ThreadStream$Companion`,
`ThreadUpdate`); `T3Api` is not packaged (see LIB_REQUESTS.md, finding 2).

On v0.3.2, `classes.jar` holds 33 classes: those ten, `T3Client$Companion`, `T3Asset`,
`T3Api` and its 20 module classes (`T3Api$Orchestration`, `T3Api$Projects`, ...).
`T3Client` adds `assetBytes`, `projectFavicon` and the `options` parameters of
`connectBearer`/`connectRelay`:

```
public final void connectBearer(java.lang.String, java.lang.String, org.json.JSONObject);
public final void connectRelay(java.lang.String, java.lang.String, java.lang.String, java.lang.String, org.json.JSONObject);
public final dev.avillagran.t3.T3Asset assetBytes(org.json.JSONObject, long, java.lang.Integer);
public final dev.avillagran.t3.T3Asset projectFavicon(java.lang.String, java.lang.String);
```

On v0.3.3, `classes.jar` holds 50 classes: `T3Api` gains `T3Api$Domain` and its 16
module classes (`T3Api$Domain$Sidebar`, `T3Api$Domain$Timeline`, ...), and `T3Client`
adds `public final java.lang.Object domain(java.lang.String, org.json.JSONObject)`. The
four `jni/<abi>/libt3_jni.so` keep 16 KiB `LOAD` alignment.

On v0.3.4, `classes.jar` holds 63 classes: 13 more `T3Api$Domain$<Module>` classes (29,
for example `T3Api$Domain$Theme` with `tokens(String, String?)` and `T3Api$Domain$I18n`
with `t`, `catalog`, `resolve` and `languages`); `T3Client` is unchanged. The AAR adds
`assets/lib-t3-rust/i18n.json`; the four `jni/<abi>/libt3_jni.so` keep 16 KiB `LOAD`
alignment. 21965936 bytes.

## 2026-10-26: awareness runtime pin v0.3.5

The Android release pin is v0.3.5, source d4bf6054b4480efdcd8a436cb66cb7f2e23664b1,
ABI3, AAR SHA256 b72a5207eaf7c075c5a24e097e150214d36e0cbf4450087b6adf9d1ab2ff4f09.
Host JNI is rebuilt from the same tag using the existing fetch/build script; the shared
lib checkout is untouched. Native registry smoke verifies 116 calls, 13 streams and
109 domain functions, including awareness.view. The i18n catalog has 1804 sources.
Notification tests additionally consume captured real awareness.view outputs; the
core tests exercise the native function for cold baseline, transition dedupe and
on-screen suppression. Build-time string generation remains pinned to the AAR asset.
