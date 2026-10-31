# Requests to lib-t3-rust

This file collects what ot3-Android needs from
[lib-t3-rust](https://github.com/avillagran/lib-t3-rust): defects found while building
and running its Android bindings, contract gaps, and missing API. ot3-Android never
re-implements protocol logic in Kotlin (see [ARCHITECTURE.md](ARCHITECTURE.md)); when
the lib lacks something, the request goes here and is resolved in the lib, then
consumed through the AAR.

Each dated section records the lib version and commit it was checked against, how it
was checked, and every finding with a proposed fix. Findings are closed by a lib
release, not by workarounds in this repository.

## 2026-10-24: v0.3.0 (4b55060), ABI 3

Checked on the development machine (x86_64 Linux, JDK 21.0.11, Kotlin 2.3.21
K2JVMCompiler from the Gradle cache, NDK 28.2.13676358) with
`scripts/fetch-or-build-lib.sh` and the lib's own `tools/build-android`,
`tools/package-android`, `tools/test-jni` and `tools/test-kotlin-jni`. The lib checkout
stayed clean at the tag throughout.

**Status:** every finding below still applies to v0.3.1 (`17d3cb7`). The fixes were
merged upstream (PR #8) and released as v0.3.2 (`26e67a7`), which is now the target.
Each finding is re-checked against the v0.3.2 release in
[2026-10-25: v0.3.2](#2026-10-25-v032): findings 1 to 4 and 6 are closed, 5 is
partially closed (`image()`'s `missing_image_bytes`) and 7 is still open. Both are
unchanged at v0.3.4 (see "2026-10-25: v0.3.4").

### What works

- `tools/build-android arm64-v8a x86_64 armeabi-v7a x86`: all four ABIs built from the
  tag; every `LOAD` segment aligned to 0x4000.
- `tools/package-android`: the rewritten `T3Client.kt` compiles against
  `android-36/android.jar` with **no errors and no warnings**; the AAR is complete.
  `T3Api.kt` together with `T3Client.kt` also compiles against `android.jar` cleanly.
- `tools/test-jni` (Java fixture `T3Native.java`): `JNI_PASS`.
- `KotlinFixture.kt` (compiled with `T3Client.kt` and `T3Api.kt`): `KOTLIN_JNI_PASS`.
- `StreamFixture.kt`, once finding 1 is corrected in a scratch copy:
  `KOTLIN_STREAMS_PASS`, 10 of 10 runs, no hang; the wake probe always cut the silent
  socket inside the asserted 2900..3400 ms window. Both Kotlin fixtures also pass with
  org.json 20250517 instead of 20180813.
- Smoke through the AAR's `classes.jar` and the host `libt3_jni.so`: `abiVersion 3`,
  `libraryVersion 0.3.0`, `apiVersion 1`, `functions 10`, 18 `operations` (the 16 ABI
  2 and transport ops plus `call` and `describe`), `pollFields` with `boundaries`,
  `idle`, `completionMarker` and `errorMessage`; `describe()` lists the 10 functions.

### 1. `tools/test-kotlin-jni` does not compile (blocker for the Kotlin fixtures)

```
bindings/android/tests/dev/avillagran/t3/StreamFixture.kt:150:13: error: 'val' cannot be reassigned.
            complete += chunks.accept(last)
            ^^^^^^^^
bindings/android/tests/dev/avillagran/t3/StreamFixture.kt:150:22: error: assignment type mismatch: actual type is 'List<List<Any?>>', but 'MutableList<List<Any?>>' was expected.
            complete += chunks.accept(last)
                     ^^
```

`complete` is a `val MutableList<List<Any?>>` and `chunks.accept(...)` returns
`List<List<Any?>>`. Because `List<List<Any?>>` is itself a `List<Any?>` (covariance),
both `plusAssign(element)` and `plusAssign(elements)` apply, so the compiler falls back
to `complete = complete + ...`, which a `val` rejects. Even if it resolved, the
single-element overload would add the whole batch as one chunk.

Proposed fix (verified: with it, `StreamFixture` passes):

```kotlin
complete.addAll(chunks.accept(last))
```

Also worth adding: a compile of the fixtures in CI (the same K2JVMCompiler command as
`tools/test-kotlin-jni`), so Kotlin sources are never tagged uncompiled.

### 2. `T3Api` is not in the AAR

`T3Api.kt` (the generated typed wrappers over `call`) lives in
`bindings/android/tests/` and `tools/package-android` compiles only `T3Client.kt`, so
`classes.jar` has no `T3Api`. The app would have to copy generated protocol code to use
it, which this repository does not do.

Proposed fix: generate `T3Api.kt` into `bindings/android/kotlin/dev/avillagran/t3/`
(update `tests/api_describe.rs` and the path in `tools/test-kotlin-jni`) and compile it
in `tools/package-android` with `T3Client.kt`. It already compiles against
`android.jar`.

### 3. `assetBytes` and `projectFavicon` are unreachable from an app module

`docs/ANDROID.md` says `T3Client` has no wrapper for them and that one is "a one-line
`T3Native.image` call". `T3Native` is `internal`, and `internal` is Kotlin-only: it is
unreachable from Kotlin source in another module, such as ot3-Android consuming the
AAR, although the bytecode is public. An app cannot make that call without
going around the Kotlin visibility rules. `T3Client.image(id, name, mimeType)` only covers the `assets.createUrl` image
path and requires bytes (`missing_image_bytes`), so it cannot express
`{found:false, sizeBytes:0}` either.

Proposed fix: public wrappers in `T3Client`, for example

```kotlin
class T3Asset(val status: JSONObject, val bytes: ByteArray?)
fun assetBytes(resource: JSONObject, maxBytes: Long? = null, timeoutMs: Int? = null): T3Asset
fun projectFavicon(cwd: String, path: String): T3Asset   // bytes == null when found == false
```

each passing `{"op":"assetBytes",...}` / `{"op":"projectFavicon",...}` to
`T3Native.image` and decoding `result[0]` like `image()` does.

### 4. `connectBearer` and `connectRelay` take no `options`

ABI 3 accepts `options` (`requestTimeoutMs`, `connectTimeoutMs`, `poolIdleTimeoutMs`,
`maxBytes`; listed in `capabilities.connectionOptions`), but the Kotlin wrappers have no
parameter for them; `connectRelay` also fixes `clientId` to `"t3-mobile"`. The app can
send the JSON through `execute`, but then builds protocol requests by hand.

Proposed fix: `options: JSONObject? = null` on both, and `clientId: String = "t3-mobile"`
on `connectRelay`.

### 5. `upload` reports a bad descriptor as `IllegalStateException`

`T3Client.upload(-1, ...)` throws `java.lang.IllegalStateException:
host_file_unavailable` from JNI, not a `T3Exception`. A host that catches
`T3Exception` for stable codes misses it.

At the raw JNI level this is an intentionally tested contract: the Java fixture
expects exactly that exception (`bindings/android/tests/dev/avillagran/t3/T3Native.java:32`).
The C++ adapter (`bindings/android/cpp/t3_jni.cpp`) throws `IllegalStateException` with
the codes `invalid_input`, `host_file_unavailable` and `native_allocation_failed`, and
`T3Client.image()` adds its own `requireNotNull(...) { "missing_image_bytes" }`
(`IllegalArgumentException`).

Proposed fix: keep the raw JNI contract and map in the Kotlin wrapper:
`host_file_unavailable`, `invalid_input` and `native_allocation_failed` (from
`IllegalStateException`) and `image`'s `missing_image_bytes` become
`T3Exception(code)`, so `T3Client` callers only catch `T3Exception`. Extend the
Kotlin fixture to assert each mapping and leave the Java fixture's raw
`IllegalStateException` assertion as it is.

### 6. No v0.3.0 GitHub release

The latest release is v0.2.0 (ABI 2). `scripts/fetch-or-build-lib.sh --release` cannot
fetch ABI 3 until the v0.3.0 release publishes `lib-t3-rust-android-v0.3.0.aar` and
its `.sha256` (ideally after finding 1 is fixed, so the release's fixtures compile).

Proposed fix: a tag-triggered release workflow (`on: push: tags: ['v*']`) that builds
the four ABIs, runs the host fixtures, packages the AAR and publishes as release assets
`lib-t3-rust-android-v<ver>.aar`, its `.sha256`, and the host
`libt3_jni-linux-x86_64.so` with its own `.sha256` (finding 7). CI here then needs no
lib checkout: `--release` (with `libs/lib-t3-rust.lock`) fetches and verifies
everything. In progress upstream for v0.3.2 (see the status above).

### 7. Releases do not publish the host JNI library

JVM unit tests in this repository load a desktop `libt3_jni.so` (see LIB_BUILD.md,
"Host JNI library for JVM tests"). Only a lib checkout can produce it today, so every
CI job that runs JVM tests must check out lib-t3-rust at the tag and have Cargo, CMake
and a JDK, even when the AAR itself comes from a release. The v0.2.0 release and the
v0.3.2 release workflow publish only the AAR and its checksum.

Proposed fix: build the host library in the release workflow exactly as
`tools/test-jni` does (`cargo build --release --locked`, CMake of
`bindings/android/cpp` into `target/jni-host`) and publish it as
`libt3_jni-linux-x86_64.so` plus `libt3_jni-linux-x86_64.so.sha256`, from the same
commit as the AAR. `scripts/fetch-or-build-lib.sh --release --host-jni` can then
download it instead of building it.

## 2026-10-25: v0.3.2

Checked against the v0.3.2 GitHub release (`26e67a7`, published 2026-10-25 00:35 UTC),
consumed through the committed `libs/lib-t3-rust.lock`, on the same machine and
toolchain as above. Source references are to the lib at the tag. The lib checkout
stayed clean.

- `gh release download v0.3.2 -R avillagran/lib-t3-rust`, then `sha256sum`:
  `3a07a7e4e6d8e60d0a568c961fafb60d867dce6094e7e9cb003a8e686c1a50fc`, equal to the
  release's `.sha256` and to GitHub's asset digest; 16503549 bytes.
- `scripts/fetch-or-build-lib.sh --release --host-jni`: checksum verified against the
  lock, `version=0.3.2 source=26e67a7… tag=v0.3.2 abiVersion=3 origin=release v0.3.2`;
  host `libt3_jni.so` built from `26e67a7` (the commit check against the AAR's
  `source=` passed).
- `scripts/fetch-or-build-lib.sh --lib-fixtures; echo exit=$?`: `JNI_PASS`,
  `KOTLIN_JNI_PASS`, `KOTLIN_STREAMS_PASS` (org.json 20180813), `exit=0`.
- Smoke through the release `classes.jar` and the host library: `abi=3 lib=0.3.2
  ops=18 functions=116 describe=116 streams=13`; `upload(-1, …)` →
  `T3Exception(host_file_unavailable)`; `connectBearer` with an unknown option →
  `T3Exception(invalid_connection_options)`; `projectFavicon` offline →
  `T3Exception(not_connected)`.
- `git diff v0.3.1 v0.3.2 -- src/ include/` is empty: the Rust core and the C ABI did
  not change, only the Android bindings, tools and workflows.

| # | Finding | Status | Evidence |
|---|---|---|---|
| 1 | `tools/test-kotlin-jni` does not compile | **Closed** | `StreamFixture.kt:150` is `complete.addAll(chunks.accept(last))`; `tools/test-kotlin-jni` prints `KOTLIN_STREAMS_PASS`. |
| 2 | `T3Api` is not in the AAR | **Closed** | `T3Api.kt` is generated into `bindings/android/kotlin/dev/avillagran/t3/` (`tests/api_describe.rs:8`) and `tools/compile-android-kotlin:13` compiles `kotlin/…/*.kt`; `jar tf classes.jar` lists `T3Api` and its 20 module classes (`T3Api$Orchestration`, …; 116 `fun` declarations in `T3Api.kt`). |
| 3 | `assetBytes` and `projectFavicon` unreachable | **Closed** | `javap`: `public final T3Asset assetBytes(JSONObject, long, Integer)` and `projectFavicon(String, String)`, both with `$default`; `T3Asset(status, bytes)` with `found` and `mimeType` (`T3Client.kt:107`, `:112`, `:132`). `maxBytes` is a `Long` defaulting to `MAX_ASSET_BYTES` (64 MiB) rather than `Long?`; equivalent. |
| 4 | `connectBearer`/`connectRelay` take no `options` | **Closed** | `javap`: `connectBearer(String, String, JSONObject)` and `connectRelay(String, String, String, String, JSONObject)` with `$default`; `clientId = "t3-mobile"`, `options = null` (`T3Client.kt:40`, `:45-47`). |
| 5 | JNI failures are not `T3Exception` | **Partially closed** | `jni {}` (`T3Client.kt:23-26`) maps `IllegalStateException` with `invalid_input`, `native_error`, `host_file_unavailable` or `native_allocation_failed` (`:127`, every code `t3_jni.cpp` throws) to `T3Exception(code)`; the Kotlin fixture asserts `host_file_unavailable` (`KotlinFixture.kt:54`). Still open: `image()` throws `IllegalArgumentException("missing_image_bytes")` (`T3Client.kt:100`, `requireNotNull(asset.bytes)`). |
| 6 | No ABI 3 GitHub release | **Closed** | Release v0.3.2 has `lib-t3-rust-android-v0.3.2.aar` and `.sha256` (`android-release.yml:49-53`, on `v*` tags); `test.yml` job `kotlin` runs `tools/compile-android-kotlin`, `tools/test-jni` and `tools/test-kotlin-jni` on every push and pull request (green on PR #8 and on the tag). |
| 7 | Releases do not publish the host JNI library | **Open** | `gh release view v0.3.2 --json assets`: only the AAR and its `.sha256`; `android-release.yml:49-53` uploads only those. CI here still checks out the lib at the tag to build it. |

Fix for 5: in `image()`, `return asset.bytes ?: throw T3Exception("missing_image_bytes")`,
and in `binary()` (`T3Client.kt:96`) replace `requireNotNull(result[0])` with
`?: throw T3Exception("native_error")`, so no `T3Client` call throws anything else for
a stable code; assert `missing_image_bytes` in `KotlinFixture.kt`. (Using a closed
client still fails with `IllegalStateException("client_closed")` from `useHandle`;
that is a caller bug rather than a stable code, so it is not part of this request.)

Fix for 7: unchanged from finding 7 above. In `android-release.yml`, build the host
library as `tools/test-jni` does and add `libt3_jni-linux-x86_64.so` and its `.sha256`
to the `softprops/action-gh-release` files, from the same commit as the AAR.

## 2026-10-24 — Capabilities requested for Android phase 1 (not defects)

Status at v0.3.3: see "2026-10-25: v0.3.3" below (items 1, 6 and 7 closed, 3 and 4
partially closed, 2, 5 and 8 open). Unchanged at v0.3.4: see "2026-10-25: v0.3.4".

These are missing pieces of API, not bugs. Each already exists in Rust (in the lib or
in ot3) but cannot be reached from Kotlin, so phase 1 (connect, live sidebar, read a
thread with streaming, send, approve and answer, choose a model) would otherwise have
to re-implement protocol logic, which [ARCHITECTURE.md](ARCHITECTURE.md) forbids.
Checked against v0.3.1 (`17d3cb7`): `src/`, `bindings/android/describe.json` (116
entries: 99 unary functions, 13 typed streams, 4 composed functions) and
`T3Client.kt`. Re-checked at v0.3.2 (`26e67a7`, the current target):
`git diff v0.3.1 v0.3.2 -- src/ include/` is empty and the v0.3.2 `T3Client.kt` and
`T3Api.kt` add none of the eight, so every request stands unchanged. ot3 references are
to omarchy-t3-code v0.2.22 (`191c1fc`).

Already delivered by v0.3.1 and therefore not requested: typed streams and `subscribe`
by `fn`, `cursor`/`start` in `pollSubscription`, the `ThreadStream` facade, the typed
registry coverage of unary T3 RPCs, the 64 MiB item limit for bounded-snapshot thread
streams (item 2) and stream probing through `wakeSubscriptions` (item 5).

None of the eight is on the ABI: `describe` has no `projection`, `sidebar`, `shell`,
`reconnect`, `connection_catalog`, `history`, `composer`, `approvals`, `questions`,
`queue` or `models` entry, and `call` only sends T3 RPCs. A pure, connection-free form
(for example `{"op":"domain","fn":"<module>.<fn>","args":{...}}`, listed in `describe`
next to the RPC functions, same error envelope) would serve items 1 to 7.

**Blocking for phase 1: items 1, 3, 6 and 8** (6 since 2026-10-25, when pairing by link
shipped). Items 2, 4, 5 and 7 are nice to have: phase 1 can work around them (no compact
snapshots, commands built from the `describe` argument schema, a Kotlin retry loop, no
older history) at some cost in behaviour or bandwidth.

### 1. Apply thread events: `projection.apply` or a reduced thread view (blocking)

- Proposed: `projection.apply` `{projection, event}` → `{projection, changed}`. Better,
  because a projection can be tens of MiB and would otherwise cross JNI on every event:
  a stateful reduced view owned by the subscription, for example
  `subscribe` `{"fn":"orchestration.subscribeThread","args":{...},"reduce":"thread"}`
  so that `pollSubscription` returns `{"items":[{"kind":"view","rev":N,"view":{...}}],
  "cursor":...}` with the lib keeping `{projection, cursor}` between polls.
- Why: the thread stream delivers a `snapshot` and then `event` items; turning them
  into the current thread (messages, turn items, runs, active run) is protocol logic.
  `ThreadStream` (v0.3.1) splits each batch into a snapshot and the events to apply,
  but leaves the applying to the host.
- In Rust today: `projection::apply(&mut Value, &Value) -> bool`
  (`src/projection.rs:8`), with the per-list caps and the field stripping that keep the
  projection bounded; `orchestration.getThreadProjection` (`call`) only fetches a
  projection. ot3 runs this loop in `src/live.rs`.

### 2. Restore compact stream snapshots (nice to have)

- Proposed: apply `bounded_snapshot::restore` to `snapshot` items of a thread stream
  opened with `acceptCompactTurnItems:true` before they are queued (or expose it as
  `bounded_snapshot.restore` in the pure form above).
- Why: with compact turn items the server omits local items from `turnItems` and ships
  them in `projection.visibleTurnItems` (`turnItemsOmitLocalVisible:true`); without the
  restore the timeline misses those items. `ThreadStream.thread(...)` defaults to
  `compactTurnItems = true` (still at v0.3.2: `T3Client.kt:316`) and `docs/ANDROID.md`
  subscribes with `acceptCompactTurnItems:true` (`:33`, `:163`, `:252` at v0.3.2), but
  nothing restores stream snapshots, so the default path hands the host an unrestored
  snapshot. Until then the app subscribes with `compactTurnItems = false`.
- In Rust today: `bounded_snapshot::restore` (`src/bounded_snapshot.rs:30`), only used
  by `boundedThread` (`src/bounded_snapshot.rs:69`, HTTP, one response).
- The 8 MiB per-item queue cap is no longer a problem: since v0.3.0 a
  `subscribeThread` stream with `acceptBoundedSnapshot:true` accepts a 64 MiB item
  (72 MiB queue, at most 2 such streams per handle; `Limits::of` in
  `src/ffi_stream.rs`, `stream::item_limit` in `src/stream.rs`). Nothing to request.

### 3. Sidebar view and shell reducer (blocking)

- Proposed: `shell.reduce` `{state, item}` → `{state, changed}` (or a
  `"reduce":"shell"` subscription, as in item 1) and `sidebar.view`
  `{shell, now, prefs:{grouping, returned, filter, query, expanded}}` →
  `{sections:[{key, title, rows[]}], counts}`; plus `sidebar.status`,
  `sidebar.metadata` and `sidebar.sortTimestamps` as individual functions.
- Why: the live sidebar (phase 1) needs the shell snapshot kept current from
  `orchestration.subscribeShell` items, then rows with status, attention reason,
  pinned/settled/snoozed state, ordering, project grouping and subagent nesting. All of
  that is decided in Rust or ot3 JS today; porting it to Kotlin would duplicate it.
- In Rust today: `sidebar::sort_timestamps`, `sidebar::sidebar_status`,
  `sidebar::metadata` (`src/sidebar.rs:6`, `:19`, `:37`) in the lib; the shell
  reducer `shell_state::reduce` (`src/shell_state.rs:83`), the row builders
  `normalize` and `snapshot` (`src/lib.rs:690`, `:826`) and the ordering and grouping
  (`ui/js/SidebarModel.js`, `ui/js/ProjectModel.js`) only in ot3. The ot3 parts would
  move into the lib first.

### 4. Command builders that return `dispatchCommand` arguments (nice to have)

- Proposed: pure functions returning the ready `args` for
  `call orchestration.dispatchCommand`: `composer.message` (ids, defaults, delivery
  mode), `approvals.command`, `questions.command`, `queue.command`,
  `models.catalog` (picker rows from `server.getConfig`), `models.runtimeModeCommand`,
  `sidebar.threadAction` / `sidebar.command`, and `projects.command` (for
  `projects.mutate`).
- Why: approve and answer, model choice and the composer are phase 1 screens. The
  host can build these JSON commands from the `describe` schema, but the validation
  rules (only a still-pending request, only an offered decision, valid runtime modes)
  live in the builders.
- In Rust today: `approvals::command` (`src/approvals.rs:61`), `questions::command`
  (`src/questions.rs:37`), `queue::command` (`src/queue.rs:31`), `models::catalog` and
  `models::runtime_mode_command` (`src/models.rs:21`, `:7`), `sidebar::command` and
  `sidebar::thread_action` (`src/sidebar.rs:63`, `:71`), `projects::command`
  (`src/projects.rs:13`); typed wrappers `Command::{queue, answer, approve,
  runtime_mode, sidebar, thread_action}` (`src/api/orchestration.rs:516-538`),
  `MessageDispatch` (`src/api/orchestration.rs:345`) and
  `ProjectMutation::from_command` (`src/api/projects.rs:119`), all Rust-only. There is
  no `composer` message builder; `call` already validates `message.dispatch` strictly
  and rewrites legacy attachment links (`src/api/orchestration.rs:1161`,
  `composer::legacy_attachment_text`).

### 5. Reconnect policy (nice to have)

- Proposed: `reconnect.nextDelay` `{failures, connectedForMs}` → `{delayMs}` and
  `reconnect.transient` `{error}` → `{transient}`.
- Why: the host owns the reconnect loop (per the lib's contract), but which errors are
  transient and the backoff curve (1 s base, 300 s ceiling, jitter, reset after 30 s
  connected) are protocol policy and should be the same as ot3's.
- In Rust today: `reconnect::transient` (`src/reconnect.rs:26`) and
  `reconnect::Backoff` with `next_delay`/`connected`/`reset` (`src/reconnect.rs:33`).
  Wakeup probing is already on the ABI (`wakeSubscriptions`, `T3Subscription.wake()`,
  `ThreadStream.wake()`), so no separate probe operation is requested.

### 6. Pairing-link parser (resolved 2026-10-25, v0.3.3; used since UI-1f)

Also U17 in the UI specification's requests below, which gates UI-6. UI_SPEC section 4.2
planned URL + token only until it lands; pairing shipped earlier on a temporary port, so
it now blocks removing that port rather than the screen.

- Proposed: an offline operation on the JSON ABI, with no handle state and no network:
  `{"op":"pairingTarget","raw":"<link or host[:port]>","code":"<typed code or empty>"}` →
  `{"httpBaseUrl":"https://host:port","pairingCode":"…"}`, failing with the function's codes
  (`invalid_endpoint`, `invalid_pairing_code`). It would be listed in `capabilities`'
  operations. Plus a Kotlin wrapper next to the `pairBearer` one (item 8), for example
  `T3Client.pairingTarget(raw: String, code: String = ""): PairingTarget` with
  `PairingTarget(httpBaseUrl, pairingCode)` and the code redacted from `toString`. (The pure
  `{"op":"domain",…}` form above would also do.)
- Why: pairing by link and QR shipped in ot3-Android on 2026-10-25, and every pairing
  starts by splitting the link. The link forms and their validation are protocol knowledge
  (the hosted `?host=` form, which parameter wins, scheme mapping, user info and port 0
  rejections, size and control-character limits), and `pairBearer` takes the already split
  `httpBaseUrl` and `pairingCode`.
- Evidence: without it, ot3-Android carries `core/pairing/PairingLink.kt`, a port of
  `pairing_target` that also has to re-implement the part of the WHATWG URL parser the
  function relies on (host normalization, IPv4 shorthand, IPv6 compression, default
  ports, form decoding of the fragment). It matches the Rust function on the 70 inputs of
  `PairingLinkTest`, captured by calling `pairing_target` at v0.3.2, plus the lib's own test
  ported case by case. Known differences remain on inputs no T3 link produces (IDNA 2003
  instead of UTS #46). It goes stale whenever the lib's rules change.
- Update 2026-10-25: lib-t3-rust v0.3.3 (`5cfb7ac`) exposes it through the
  connection-free `domain` operation as `connectionCatalog.pairingTarget` `{link,
  pairingCode?}` → `{httpBaseUrl, pairingCode}`, with a `T3Api` wrapper. The app still pins
  v0.3.2, so `PairingLink.kt` stays until it moves to v0.3.3; `PairingLinkTest`'s table
  can then check the wrapper instead.
- Resolved 2026-10-25 (UI-1f): `PairingLink.kt` is deleted. `core/pairing/PairingTargets.kt`
  calls `connectionCatalog.pairingTarget` through `DomainEngine` and returns the redacted
  `PairingTarget`; neither the arguments nor the result are logged. `PairingTargetsTest` runs
  the same 70-input table (and the lib's own test) against the domain operation through the
  host JNI library: every case matches what the Rust function returned at v0.3.2.
- In Rust today: `connection_catalog::pairing_target` (`src/connection_catalog.rs:5-58`,
  test at `:265-290`); the catalog functions `view`, `mutate` and `register` (`:73`, `:107`,
  `:213`) could follow, with storage staying in Kotlin.

### 7. History paging (nice to have)

- Proposed: `history.page` `{threadId, cursor}` as a `call` function that fetches the
  page and returns the converted envelope (or `history.path` and `history.envelope` in
  the pure form).
- Why: older messages beyond the snapshot window. The path can be built by hand and
  fetched with `request`, but converting the page rows needs Rust.
- In Rust today: `history::path` and `history::envelope` (`src/history.rs:3`, `:20`).
  `describe` has no history function.

### 8. Kotlin wrappers for the remaining operations (blocking)

- Proposed: `T3Client` methods for `fetchDescriptor(httpBaseUrl, environmentId?,
  options?)`, `pairBearer(httpBaseUrl, pairingCode, environmentId?, client?, connect,
  options?)`, `boundedThread(threadId)`, `requestOk(method, path, body?, protocol)`,
  `rpcWithTimeout(tag, payload, timeoutMs)`, `capabilities()`, `disconnect()`, `request`
  with `method` and `body` (POST), and `shell()` (`GET /api/orchestration/shell`, today
  the one HTTP path ot3-Android builds in Kotlin).
- Why: phase 1 connects by pairing (`fetchDescriptor`, then `pairBearer`) and reads
  `capabilities` to gate features. Through `execute(JSONObject)` the app would assemble
  operation JSON by hand, which is the protocol knowledge the adapter exists to hold.
- In Rust today: all of them are ABI operations (`src/ffi.rs`: `request` and
  `requestOk` take `method`, `path`, `body`; `fetchDescriptor`, `pairBearer`,
  `boundedThread`, `rpcWithTimeout`, `capabilities`). `T3Client.kt` at v0.3.1 (and
  v0.3.2) wraps none of them, and `request(path, protocol)` is GET-only.
- Update 2026-10-25: pairing now calls `pairBearer` through `execute` in
  `NativeT3Transport.pairBearer`, building `{"op":"pairBearer","httpBaseUrl",
  "pairingCode","environmentId","client":{label, deviceType, os},"connect"}` and reading
  `{accessToken, tokenType, environmentId, descriptor, scope, expiresIn, connected}` by hand.
  `NativePairingTest` pins that shape against a local server. A `T3Client.pairBearer`
  returning a typed result with the token redacted from `toString` would replace both.

## 2026-10-25: v0.3.2 through the app's transport layer

Checked with `NativeT3Transport` over the v0.3.2 release AAR and the host
`libt3_jni.so` (`26e67a7`): the JVM tests in `core/src/test/.../transport` and one
manual, read-only live run against a local T3 server (`connect(Bearer)`,
`request("/api/orchestration/shell")` (now `shell()`), `shellStream()` and `threadStream(id)`; shapes
only, no mutation). Confirmed as expected: `capabilities` (`abiVersion 3`,
`libraryVersion 0.3.2`), `describe` (116 functions, 13 streams), validation before
connecting (`invalid_cwd`, `unknown_function`, `invalid_thread_id`), `unauthorized` on a
401, `fetchDescriptor` without a credential, `t3_environment_mismatch`,
`invalid_connection_options` for both routes, and closing a subscription from another
thread waking a blocked `poll` at once.

### 1. A shell metadata snapshot replaces the shell and moves the cursor

- What: on `orchestration.subscribeShell`, T3 follows the authoritative snapshot with a
  second `kind: "snapshot"` item that carries `resolvedRepositoryIdentityRoots` and only
  refreshes project identities. The lib treats it as a full state: `start_of` returns
  `Snapshot` for every shell `snapshot` (`src/stream.rs:52-55`) and `advance` sets the
  cursor to its `snapshot.snapshotSequence` (`:21`, `:33-36`), and `ThreadStream.poll`
  returns it as `ThreadUpdate.snapshot`, dropping the events before it in the batch
  (`bindings/android/kotlin/dev/avillagran/t3/T3Client.kt:290`). A host that
  follows the documented loop ("replace whatever the host held") replaces its sidebar
  with a shell that has no threads.
- Evidence: the live shell stream, first two polls: `items=[snapshot]` with
  `snapshot{archivedThreads[0], projects[16], schemaVersion, snapshotSequence,
  threads[213]}` and `boundaries=[]`, then `items=[snapshot]` with
  `{kind, resolvedRepositoryIdentityRoots[16], snapshot{..., projects[16],
  threads[0]}}` and `boundaries=[1]` (the same server chunk), then `idle`. ot3's reducer
  treats such an item as metadata only and keeps the cursor (`src/shell_state.rs:87-107`
  in omarchy-t3-code v0.2.22, test `metadata_refresh_merges_identities_without_moving_the_cursor`
  at `:223`: "a resume prefix must not skip replayed events"), following T3's
  client-runtime `state/shellReducer.ts`.
- Two more consequences of the same rule:
  - When the metadata item shares a poll batch with the authoritative snapshot (a host
    that polls a little later than the server writes, as the live run's single chunk
    shows), `ThreadUpdate.snapshot` is the metadata item, because the last snapshot of a
    batch wins, and the authoritative snapshot survives only in `batch.items`.
  - When it is the first item after a resume (`afterSequence` set), `start` says
    `"snapshot"` instead of `"resume"`, so a host that follows `start` drops the state it
    resumed from.
- Proposed fix: keep the metadata item out of `snapshot`. Either give `ThreadUpdate` a
  `metadata` field (the item, in order, with the events before it kept), or, better and
  the same work, phase 1 item 3 (`shell.reduce`), which applies it as ot3 does. In both
  cases `stream::start_of` must not return `Start::Snapshot` for a shell `snapshot` with
  `resolvedRepositoryIdentityRoots`, and `cursor_of` returns `None` for it (so `advance`
  leaves the cursor alone). Add the frame to `StreamFixture`, alone and sharing a batch
  with the authoritative snapshot.
- Workaround in ot3-Android until then: one shell adapter in `core.transport`
  (`ThreadUpdate.shellSnapshot()`, `isShellMetadataOnly()` and the helpers next to them)
  that every shell consumer goes through. It treats a metadata item as metadata only
  (never replaces the sidebar, never trusts `start` for it), does not adopt the cursor it
  moved, and reads the authoritative snapshot from `batch.items` when the metadata item
  shadows it in `ThreadUpdate.snapshot`. This is protocol knowledge in Kotlin, against
  the rule in ARCHITECTURE.md, accepted only as a stopgap: it is deleted when a lib
  release with the fix above lands.

**Update 2026-10-25** (same server, v0.3.2, `T3Client` directly and the app's
transport, read-only): the metadata frame also arrives **in the same batch** as the
authoritative snapshot (`items=[snapshot{threads[216]}, snapshot{resolvedRepositoryIdentityRoots[16],
threads[0]}]`, `boundaries=[2]`). `ThreadStream.poll` keeps only the last `snapshot`
(`T3Client.kt:290`), so `ThreadUpdate.snapshot` is the empty metadata frame and the
authoritative snapshot is not in the update at all; a host that follows the documented
loop paints an empty sidebar. Metadata frames also recur later in a running stream (one
for a single project, `resolvedRepositoryIdentityRoots[1]`; one subscription received
13 `snapshot` items in 40 minutes, 11 of them after the first batch). ot3-Android works around it with the same
adapter (`core/transport/ShellUpdates.kt`, which reads `update.batch.items` in order and
derives the cursor without the metadata frame); `ShellReducer` only consumes it. The
proposed fix above (or item 3, `shell.reduce`) removes both.

**Status at v0.3.4: open.** `src/stream.rs` and `T3Client.kt` are byte-identical to v0.3.3
(`git diff v0.3.3 v0.3.4` is empty for both), so `start_of` still returns `Snapshot` for a
metadata snapshot and `ThreadStream.poll` still keeps only the last `snapshot` of a batch.

### 2. `rpc_rejected` carries no machine-readable field or reason code

Found 2026-10-25 while building the thread screen's send (v0.3.2, `T3Exception` and
`src/api/orchestration.rs`; nothing was sent to a live server).

- What: when the server refuses a command, the app gets `T3Exception("rpc_rejected",
  reason)` with `reason` as free text (a schema error or a sentence). Nothing says which
  argument was refused or why in a stable form, so the app cannot react to it (for
  example drop `creationSource: "mobile"`, which the lib's validator allows, if a server
  refuses it) without parsing English or schema text. The previous send code did exactly
  that (it looked for `creationSource` or `"mobile"` in `reason` and resent with `"web"`);
  it was removed in favour of always sending `"web"`, as ot3 does.
- Evidence: `CREATION_SOURCES = ["web", "mobile", "mcp", "provider", "server"]`
  (`src/api/orchestration.rs:11`), while ot3 always sends `"web"` (`src/write.rs:149` in
  omarchy-t3-code) and so does the lib's own compaction command (`src/compaction.rs:154`);
  no live server has been seen accepting `"mobile"`. `T3Exception` has `code`, `reason` and `requiredScope` only.
- Proposed fix: add `rejectedField` and `rejectedReason` (a stable code such as
  `invalid_enum`, `too_long`, `not_found`) to `T3Exception` when the server's rejection
  names them, or, for this case, list the values the connected server accepts for
  `creationSource` in the descriptor or in `describe`, so the app never needs a rejection
  to learn them.

### 3. `ThreadUpdate` cannot say whether the lib's reducer applied an event

Found 2026-10-25 with the thread screen (v0.3.2), read-only against the same server.

- What: a host that follows a thread stream has to decide, per event, whether its view
  changed, was left alone (a row it does not show), or does not know the type. The lib
  knows (`projection::apply` returns `bool`), but `ThreadUpdate` only carries the raw
  items. The first version of the thread screen marked the view stale and refetched
  `boundedThread` for every type outside a small Kotlin list; on a live thread that is a
  refetch per `turn-item.updated`, i.e. a refetch storm while an agent works.
  ot3-Android now mirrors the lib's apply table in Kotlin
  (`core/.../thread/ThreadProjectionShim.kt`: applied, ignored, unknown to the lib), which
  is protocol knowledge outside the lib and goes stale with every new event type.
- Evidence: the live thread used for the check held 477 turn items, 340 nodes and 106
  messages, and the first event after subscribing was `thread.visited`, a type that is
  not in `src/projection.rs`'s table (`apply` returns false: only the cursor moves).
- Proposed fix: preferred, phase 1 item 1 (a reduced thread view owned by the
  subscription), which removes the shim altogether. Short of that, a per-item
  `applied: Boolean` in `ThreadUpdate` (the result of the lib's `projection::apply` on a
  projection the lib holds for the subscription), so the host never classifies event
  types itself.

**Phase 1 item 1 is now the main phase-1 blocker** (2026-10-25). Items 3 and 8 still
block, but item 1 is the one with live cost: the bounded snapshot of a busy thread is large
(477 turn items, 340 nodes, `payloadBudgetExceeded = true`), so refetching it is not an
answer to an event it cannot apply, and the Kotlin mirror of `projection::apply` it
forces is the largest piece of protocol logic left in the app.

## 2026-10-25: requests from the UI specification

Status at v0.3.3: see "2026-10-25: v0.3.3" below (U0, U1, U2, U3, U4, U5, U6, U7, U8,
U10, U17, U18 and U21 closed; the rest in progress in the lib). **Status at v0.3.4: every
row closed**; see "2026-10-25: v0.3.4" below.

Source: `docs/UI_SPEC.md` section 6 (merged in #7), which maps every screen of ot3-Android
v1 to the lib function it reads. This section moves the rows marked **new** there, plus
U2, whose scope the spec extends; the other U rows restate existing items above (U1 =
phase-1 item 1, U5 / U6 / U7 / U8 = item 4 and the pure-form list, U17 = item 6, U18 =
item 7). Citation prefixes (`lib:`, `ot3:`) and pins are those of UI_SPEC section 0
(lib v0.3.2 `26e67a7`, ot3 `7b5b1de`).

All of them rely on the connection-free operation proposed at the start of the phase-1
section (`{"op":"domain","fn":"<module>.<fn>","args":{...}}`, or the equivalent typed
`call` without a socket), returning JSON the app renders without further derivation.

**Order agreed with the lib (2026-10-25), already in progress there:** U2, U21, U0, U3,
U4, U10, U5, U7, U8. Gating per UI tranche: nothing blocks UI-1 (U2 and U21 complete
it); U0, U3, U4 and U10 gate UI-2; U5, U7 and U8 gate UI-3; U6, U15 and U24 gate UI-4;
U9, U19, U20 and U22 gate UI-5; U14, U17 and U23 gate UI-6; U12 gates UI-7.

### Extended existing request

| # | Request | In Rust today | Screen | Until then |
|---|---|---|---|---|
| U2 | `sidebar.view` + shell reducer (existing item 3), **extended to several environments' shells in one view** and to ot3's ten-state detail and counts | `sidebar::sidebar_status`, `metadata`, `sort_timestamps` (`lib:src/sidebar.rs:6,19,37`); detail, grouping and order only in ot3 (`ot3:ui/js/SidebarModel.js:5-125`, `ot3:ui/js/ProjectModel.js:4-12`) | Home (4.1), count pills | `SidebarModel.kt`, extended to several environments |

### New requests

| # | Request | In Rust today | Screen | Until then |
|---|---|---|---|---|
| U0 | **new** Timeline view: ot3's `conversation_timeline` rows (user, assistant, activity groups, changes, context, plan, secret, subagents, error, each with a `rev`) and `live_activity`, from a projection or the reduced view of item 1 | **ot3 only**: `ot3:src/lib.rs:532-617` | Thread (4.3), working pill (4.4.1) | user and assistant messages from `ThreadProjectionShim` (`core/.../thread/ThreadProjectionShim.kt:54`) |
| U3 | **new** `activity.describe` | `lib:src/activity.rs:38` | Activity blocks (4.3.3) | blocks hidden |
| U4 | **new** `changes.summaries` | `lib:src/changes.rs:4` | Changed-files card (4.3.4), diff ranges (4.6.2) | card hidden |
| U9 | **new** `diffReview.present` | `lib:src/diff_review.rs:100` | Diff (4.6) | file list without hunks |
| U10 | **new** Markdown as renderer-neutral blocks (paragraphs, headings, lists, quotes, tables, code with language and runnable flag, images, links classified GitHub / file / thread / web with ot3's allow-list); the lib says an Android surface "gets its own variant once its renderer is designed" (`lib:README.md:602-612`) | ot3's renderer emits Qt rich text (`ot3:src/lib.rs:119-171`); `code_blocks::parse` already returns fenced blocks as data (`lib:src/code_blocks.rs:12`) | Messages (4.3.2) | display-only CommonMark in Kotlin; only http(s) links tappable |
| U11 | **new** `theme.tokens`: Omarchy `colors.toml` in, ot3 roles out (3.1) | **ot3 only**: `ot3:ui/qml/T3Palette.qml`, `ot3:ui/qml/T3Panel.qml:380-387`, `ot3:ui/js/DocumentTheme.js` | every screen | precomputed by the generator with goldens |
| U12 | **new** `timeline.minimap` | **ot3 only**: `ot3:ui/js/TimelineModel.js:4,16` | Tablet minimap (4.3.8) | no minimap |
| U13 | **new** `subagents.buckets` and labels | **ot3 only**: `ot3:ui/js/Subagents.js:8-54` | Subagent cards (4.3.6) | hidden |
| U14 | **new** `usageSummary.request` / `aggregate`, `quotaSources.limits` / `providers` / `sources` | `lib:src/usage_summary.rs:23,40`, `lib:src/quota_sources.rs:23,75,86` | Usage (4.10) | page hidden |
| U15 | **new** `planWorkflow.view` / `followUp` with a renderer that returns Markdown | `lib:src/plan_workflow.rs:191,325` | Plan card and banner (4.3.7) | plan as a message |
| U16 | **new** ship ot3's string catalog with the lib (or the AAR) | `i18n::translate` has no catalog (`lib:src/i18n.rs:4`); catalog in `ot3:translations/i18n.json` | every screen | generated `strings_ot3.xml` |
| U19 | **new** `pullRequests.sidebarBadge`, `pullRequests.payload` | `lib:src/pull_requests.rs:82,268` | Row PR badge (3.7), PR comment, review and actions (4.8) | no badge; arguments built from the `describe` schema |
| U20 | **new** `branchPicker.dedupe` / `selectionTarget` / `mismatch` | `lib:src/branch_picker.rs:9,41,90` | Branch picker, branch mismatch notice (4.7.3, 4.4.1) | refs listed as returned |
| U21 | **new** project identity: monogram and colour slot | **ot3 only**: `ot3:src/lib.rs:729-755`, `ot3:ui/qml/T3Palette.qml:36-46` | Project chip everywhere (3.7) | first letter of the title, accent tint |
| U22 | **new** `gitControls.scope` / `actions` / `present` / `buildAction` | `lib:src/git_controls.rs:37,185,203,245` | Git & changes sheet (4.6.3) | status and Review only |
| U23 | **new** `nativeSettings.snapshot` / `patch`, `scopedSettings.effective` / `projectPatch` | `lib:src/native_settings.rs:107,152`, `lib:src/scoped_settings.rs:38,56` | Settings pages (4.9) | read-only |
| U24 | **new** `checkpointRevert.canPreview` / `reference` / `command` / `observation` / `recoveryMessage` | `lib:src/checkpoint_revert.rs:7,39,159,208,248` | Edit from here (4.5.4) | not offered |
| U25 | **new** `toolInspector.scope` / `request` / `parse` | `lib:src/tool_inspector.rs:61,89,236` | Inspector (4.3.3) | not offered |

Proposed fix, common to every row: expose the Rust function (moving it from ot3's crate
into the lib first where the row says "ot3 only") through the connection-free operation,
add it to `describe` with typed args, generate the `T3Api` wrapper, and cover it with a
fixture test whose JSON the app can reuse as screenshot-test input.

## 2026-10-25: v0.3.2 pairing through the app's transport layer

Checked with `NativeT3Transport.pairBearer` over the v0.3.2 release AAR and the host
`libt3_jni.so` (`26e67a7`): `NativePairingTest` against a local server serving the
descriptor and `POST /oauth/token`, and one live pairing against a local T3
0.1.3-nightly.20261025.3669 from a `t3 pair` link (`LivePairingTest`). The session it
created was revoked afterwards. Confirmed as documented: the descriptor is read and its
`environmentId` checked (UUID, expected id) before the code is sent; input errors
(`invalid_pairing_code`, `invalid_pairing_client`, `invalid_endpoint`) fail before any
request; the form-encoded token exchange carries the client's label, device type and OS;
`connect: true` connects the handle. Item 6 above (U17) moved to blocking with this
work.

### 1. `pairing_rejected` also covers server errors

- What: `pair_bearer` maps every failed exchange to `pairing_rejected`: `unauthorized` (401,
  403) and every `http_<n>`, including 5xx (`src/pairing.rs`, the `map_err` after
  `decode`). An app then tells the user the code is bad ("expired or already used, run
  `t3 pair` again") when the server failed or a proxy in front of it answered 502.
  Re-running `t3 pair` does not help with that, and each run mints another credential.
- Evidence: `NativePairingTest.aRefusedCodeIsPairingRejected` gets `pairing_rejected` for
  400, 401, 403 and 500 alike. The OAuth error body (`{"error":"invalid_grant"}` in the
  fixture) is dropped, so the app cannot tell an expired code from an unknown one either.
- Proposed fix: keep `pairing_rejected` for 400, 401 and 403 (an OAuth refusal), and
  return `http_<n>` for anything else, as every other operation does. Put the OAuth
  `error` value (when it is a short ASCII token) in the exception's `reason`.

### 2. Pairing again leaves the previous session alive, and Kotlin cannot revoke it

- What: re-pairing an environment that is already saved (a lost or reset token,
  `unauthorized` after a server change) gets a new session. The app replaces the stored
  token, but the old session stays active on the server, and every re-pair adds one
  more. ot3 retires its own stale sessions with `Connection::revoke_stale_sessions`
  (`src/pairing.rs:189-210`: `GET /api/auth/clients`, a host policy, `POST
  /api/auth/clients/revoke` `{sessionId}`). That function is Rust-only: no ABI operation,
  no `describe` entry, and `T3Client.request` is GET-only.
- Evidence: the live run's session showed up in `t3 auth session list` with the client
  label the app sent. It could only be removed from the server's CLI (`t3 auth session
  revoke`), not from the app.
- Proposed fix: two typed operations on a connected handle, `auth.sessions` →
  `{current: sessionId, sessions: [{sessionId, client{label, deviceType}, issuedAt,
  lastConnectedAt, connected}]}` and `auth.revokeSession {sessionId}` (refusing the current
  session). Then the app revokes the session whose token it just replaced. Alternatively,
  a `pairBearer` option `replaceSession: <old token>` that revokes it once the new session
  works.

## 2026-10-25: v0.3.3

Checked against the v0.3.3 GitHub release (`5cfb7ac`, published 2026-10-25 18:35 UTC),
consumed through `libs/lib-t3-rust.lock`, on the same machine and toolchain as above.
Source references are to the lib at the tag; the lib checkout stayed at the tag and clean.

- `gh release download v0.3.3 -R avillagran/lib-t3-rust`, then `sha256sum`:
  `183e233edd1b9250976463f952210781eba85f33f64493498e0b169f68c861e4`, equal to the
  release's `.sha256`; 17367953 bytes.
- `scripts/fetch-or-build-lib.sh --release --host-jni`: checksum verified against the
  lock, `version=0.3.3 source=5cfb7ac… tag=v0.3.3 abiVersion=3 origin=release v0.3.3`;
  host `libt3_jni.so` built from `5cfb7ac`.
- `scripts/fetch-or-build-lib.sh --host-jni-only --lib-fixtures`: `JNI_PASS`,
  `KOTLIN_JNI_PASS` (now including the domain functions), `KOTLIN_STREAMS_PASS`, exit 0.
- `capabilities`: `abiVersion 3`, `libraryVersion 0.3.3`, 19 `operations` (`domain`
  added), `functions 116`, `domainFunctions 37`; `describe`: 116 `functions`, 13
  `streams`, 37 `domain` (pinned in `NativeSmokeTest`). `classes.jar` has 50 classes
  (`T3Api$Domain` and 16 module classes added).
- Read-only live check against the local T3 (0.1.3-nightly, protocol 2) with a 30-minute
  token, revoked afterwards: `LiveConnectionTest` and `LiveThreadTest` pass;
  `sidebar.view` over 233 shell threads (203 subagents) and 16 projects gives `active`
  36, `snoozed` 0, `settled` 24 rows and 16 project identities; `timeline.view` of a
  running thread gives 45 rows (15 assistant, 5 user, 20 activity, 5 subagents), each with
  a `rev`, and the session paints them while live.
- `git diff v0.3.2 v0.3.3 -- src/stream.rs src/bounded_snapshot.rs src/reconnect.rs
  src/composer.rs src/api/orchestration.rs` is empty; `T3Client.kt` only adds `domain`.

### Requests from the UI specification

| # | Request | Status | Evidence |
|---|---|---|---|
| U0 | Timeline view | **Closed in v0.3.3** | `timeline.view`, `timeline.liveActivity` in `describe.domain`. `ThreadSession` paints from it; `ThreadTimelineTest` (`applyUpsertsMessagesAndKeepsTheActiveRun`, `theKitchenSinkKeepsMessagesAsBubblesAndTheRestForUi2` on the lib's `timeline-projections.json`), `ThreadSessionTest.eventsGoThroughTheLibInOneCallPerUpdate`, `DomainContractTest.u0LiveActivity`. |
| U1 | Reduced thread view (phase 1 item 1) | **Closed in v0.3.3** | `projection.apply` (with `applied`) + `timeline.view`. `ThreadProjectionShim.kt` is deleted; `ThreadTimelineTest.typesTheLibDoesNotKnowChangeNothing`, `ThreadSessionTest.threadVisitedOnlyMovesTheCursor`. The lib-held reduced view was not taken; its cost is new finding 3. |
| U2 | `sidebar.view` over several environments | **Closed in v0.3.3** | `sidebar.view` (sections, `sectionCounts`, `counts`, `filterCounts`, `projectGroups`, `projectIdentities`, rows with `detail`, `state`, `statusLabel`, `statusRole`, `time`, `environmentId` filter). `SidebarModel.kt` is deleted; `SidebarViewTest` (the lib's `sidebar-environments.json`: three environments, the ten details, labels and roles, time labels, `environmentId`, malformed shells). The shell reducer half of the request stays open: new finding 1. |
| U3 | `activity.describe` | **Closed in v0.3.3** | `activity.describe`; `DomainContractTest.u3ActivityDescribe`. No screen calls it yet (UI-2); `timeline.view` activity rows already carry it. |
| U4 | `changes.summaries` | **Closed in v0.3.3** | `changes.summaries`; `DomainContractTest.u4ChangesSummaries`. No screen yet (UI-2). |
| U5 | `queue.derive`, `queue.command` | **Closed in v0.3.3** | Both in `describe.domain`; `DomainContractTest.u5QueueDeriveAndCommand`; `timeline.view` returns `queue` too. No screen yet (UI-3). |
| U6 | `approvals.*`, `questions.*` | **Closed in v0.3.3** | `approvals.pending` / `command`, `questions.pending` / `command`; `DomainContractTest.u6ApprovalsAndQuestions`; the banners now read `timeline.view` `approvals` and `questions` (`ThreadTimelineTest.approvalsAndQuestionsBecomeBanners`). Answering waits for UI-4. |
| U7 | `models.*` | **Closed in v0.3.3** | `models.catalog`, `preferences`, `favoritePayload`, `runtimeModeCommand`; `DomainContractTest.u7ModelsAndRuntimeMode` (catalog, runtime mode command); `preferences` and `favoritePayload` verified by `describe` only. |
| U8 | `composer.paths`, `composer.catalog` | **Closed in v0.3.3** | Both plus `composer.fileLink` and `attachmentReference`; `DomainContractTest.u8ComposerCompletions` (paths, file link); `catalog` verified by `describe` only (it needs a provider object). |
| U10 | Markdown as renderer-neutral blocks | **Closed in v0.3.3** | `markdown.blocks`, `markdown.link` (and `codeBlocks.parse`); assistant rows and plans of `timeline.view` carry `blocks`. `DomainContractTest.u10MarkdownBlocksAndLinks`, `ThreadTimelineTest.blocksFlattenToPlainText` (bubbles show them as plain text until UI-2). |
| U17 | Pairing-link parser (phase 1 item 6) | **Closed in v0.3.3** | `connectionCatalog.pairingTarget` `{link, pairingCode?}` → `{httpBaseUrl, pairingCode}`; `DomainContractTest.u17PairingTarget`. `feat/pairing` still has a temporary `PairingLink.kt`: that branch must switch to this function. |
| U18 | History paging (phase 1 item 7) | **Closed in v0.3.3** | `history.path`, `history.envelope`; `DomainContractTest.u18HistoryPath`; `envelope` verified by `describe` only. The app does not page yet. |
| U21 | Project identity | **Closed in v0.3.3** | `projects.identity`, `projects.monogram`, and `sidebar.view` `projectIdentities`; the sidebar rows show the chip. `DomainContractTest.u21ProjectIdentity`, `SidebarViewTest.rowsCarryKeysProjectsAndChips`, `NativeSmokeTest.domainRunsWithoutAConnection`. |
| U9, U11, U12, U13, U14, U15, U16, U19, U20, U22, U23, U24, U25 | the rest of section 6 | **In progress in the lib** | None of `diffReview.*`, `theme.*`, `timeline.minimap`, `subagents.*`, `usageSummary.*` / `quotaSources.*`, `planWorkflow.*`, a string catalog, `pullRequests.*`, `branchPicker.*`, `gitControls.*`, `nativeSettings.*` / `scopedSettings.*`, `checkpointRevert.*` or `toolInspector.*` is in the 37 `describe.domain` entries. (`timeline.view` already returns `planView` and subagent summaries, which UI-2 can use before U13 and U15.) |

### Phase 1 capabilities

| Item | Status | Evidence |
|---|---|---|
| 1. Apply thread events | **Closed in v0.3.3** | U0 and U1 above. |
| 2. Compact stream snapshots | Open (unchanged) | `src/stream.rs` and `src/bounded_snapshot.rs` did not change; `compactTurnItems` stays false. |
| 3. Sidebar view and shell reducer | **Partially closed** | `sidebar.view` closes the view (U2). No shell reducer: `ShellReducer.kt` stays, marked temporary (new finding 1). |
| 4. Command builders | **Partially closed** | `approvals.command`, `questions.command`, `queue.command`, `models.runtimeModeCommand`, `sidebar.command`, `sidebar.threadAction`, `models.catalog` are in `describe.domain`. Still missing: a `message.dispatch` builder, a `run.interrupt` builder and `projects.command`, so `MessageSender` keeps building those arguments (new finding 2). |
| 5. Reconnect policy | Open | No `reconnect.*` in `describe.domain` (`src/reconnect.rs` unchanged); `Backoff.kt` stays. |
| 6. Pairing-link parser | **Closed in v0.3.3** | U17. |
| 7. History paging | **Closed in v0.3.3** | U18 (pure form). |
| 8. Kotlin wrappers for the remaining operations | Open | `T3Client.kt` adds only `domain`; `fetchDescriptor`, `pairBearer`, `boundedThread`, `requestOk` and the shell GET are still assembled in `NativeT3Transport`. |

### Earlier findings

| Finding | Status | Evidence |
|---|---|---|
| v0.3.2 transport 1: a shell metadata snapshot replaces the shell and moves the cursor | **Open** | `start_of` still returns `Snapshot` for every shell `snapshot` (`src/stream.rs:54`) and `ThreadStream.poll` still keeps only the last one and clears the events before it (`T3Client.kt:297`). Seen again live on 2026-10-25: the first shell batch holds the authoritative snapshot (233 threads) and two metadata snapshots (`resolvedRepositoryIdentityRoots` of 16 and 1 roots, `threads[0]`). `ShellUpdates.kt` stays. New finding 1 would close it. |
| v0.3.2 transport 2: `rpc_rejected` without a machine-readable field | Open | `T3Exception(code, reason, requiredScope)` unchanged (`T3Client.kt:147`). |
| v0.3.2 transport 3: no per-event `applied` | **Closed in v0.3.3** | `projection.apply` returns `applied`; the app no longer classifies event types. |
| v0.3.0 5: `image()` throws `IllegalArgumentException` | Open | `requireNotNull(asset.bytes) { "missing_image_bytes" }` (`T3Client.kt:107`). |
| v0.3.0 7: releases do not publish the host JNI library | Open | Release v0.3.3 assets: the AAR and its `.sha256` only. |

### 1. No shell reducer in `domain`

- What: `sidebar.view` takes each environment's `shell` as "its
  `orchestration.getShellSnapshot` (or the live shell stream's state)"
  (`src/sidebar/view.rs:155-157`), but nothing in the lib turns the shell stream into that
  state. ot3-Android keeps `core/shell/ShellReducer.kt` (a port of ot3's
  `src/shell_state.rs`) and `core/transport/ShellUpdates.kt` (the metadata-snapshot
  workaround) for it: protocol logic outside the lib.
- Evidence: no `shell.*` function among the 37 `describe.domain` entries; metadata
  snapshots seen live as above.
- Proposed fix: `shell.apply` `{shell?, items}` (the raw stream items of one update, in
  order) → `{shell, cursor, changed}`: an authoritative `snapshot` replaces `shell`
  keeping resolved repository identities, a snapshot with
  `resolvedRepositoryIdentityRoots` only merges those identities and does not move the
  cursor, `project.*` / `thread.*` events apply after it, as ot3's `shell_state.rs`
  does. This also closes the metadata-snapshot finding for every host.
- **Status at v0.3.4: open.** No `shell.*` among the 108 `describe.domain` entries.

### 2. No `message.dispatch` or `run.interrupt` builder

- What: sending and Stop build their `orchestration.dispatchCommand` arguments in Kotlin
  (`MessageSender.messageCommand`, the interrupt command) from the lib's validator.
- Evidence: `composer.*` in `describe.domain` is `paths`, `catalog`, `fileLink` and
  `attachmentReference`; `MessageDispatch` stays Rust-only (`src/api/orchestration.rs:345`),
  as does the `run.interrupt` arm (`:665`).
- Proposed fix: `composer.message` `{threadId, commandId, messageId, text, attachments?,
  modelSelection?, dispatchMode?, deliveryIntent?}` → the ready arguments (the host mints
  and persists the ids before sending, so they stay inputs), and
  `composer.interruptCommand` `{threadId, runId, commandId}`.
- **Status at v0.3.4: open.** `composer.*` is unchanged (the same four functions) and
  `src/api/orchestration.rs` did not change. `planWorkflow.attachSourceRef` takes a
  ready `message.dispatch` command and only adds `sourcePlanRef` to it, so it does not
  build one.

- **Rechecked 2026-10-26 against tag v0.3.5:** `awareness.view` landed, but the
  four `composer.*` functions above are unchanged. UI-3 queue mutations and UI-4
  approvals/questions now use their native builders. New-message queue/steer, attachment
  dispatch, and the UI-4 plan follow-up still need the common submission builder.
  `planWorkflow.followUp` supplies text/interactionMode/sourcePlanRef, and
  `attachSourceRef` decorates an existing dispatch; neither builds/executes the required
  interaction-mode + message sequence with stable retry ids. Extend the common builder
  to cover this recipe instead of adding another protocol sequence to Kotlin.

### 3. The projection crosses JNI twice per stream update

- What: with `projection.apply` and `timeline.view` the whole projection is serialised to
  the lib and back on every update, then again for the view.
- Evidence: live bounded projection of 1.6 MiB (104 turn items, 346 nodes): about 50 ms per
  `projection.apply` and 35 ms per `timeline.view` (mean of 5, node x86_64, host
  library), so about 85 ms of CPU per painted update on a desktop and several times that
  on a phone, plus the JSON copies in Kotlin.
- Proposed fix (nice to have): keep the projection in the lib, e.g. `projection.open`
  `{snapshot}` → `{handle}`, `projection.apply` `{handle, events}` → `{applied}`,
  `timeline.view` `{handle, …}`, and `projection.close`; or let `timeline.view` take
  `events` to apply first and return the projection only on request.
- **Status at v0.3.4: open.** `projection.apply` and `timeline.view` take and return the
  same JSON as in v0.3.3 (`src/projection.rs` unchanged; `src/timeline.rs` only adds the
  `minimap` module). The new functions that read a projection (`planWorkflow.view`,
  `checkpointRevert.*`) take it whole as well.

## 2026-10-25: v0.3.4

Checked against the v0.3.4 GitHub release (`e2ec546`, published 2026-10-25 19:29 UTC),
consumed through `libs/lib-t3-rust.lock`, on the same machine and toolchain as above.
Source references are to the lib at the tag; the lib checkout was moved to the tag and
stayed clean.

- `gh release download v0.3.4 -R avillagran/lib-t3-rust -p '*.aar*'`, then `sha256sum -c`
  and `sha256sum`: `b90064ae893cf0239a3d19b3bf213ebb8c92d6e14da0a8d4a4ff9ae083bfc036`,
  equal to the release's `.sha256` and to GitHub's asset digest; 21965936 bytes.
- `scripts/fetch-or-build-lib.sh --release --host-jni`: checksum verified against the
  lock, `version=0.3.4 source=e2ec546… tag=v0.3.4 abiVersion=3 origin=release v0.3.4`;
  host `libt3_jni.so` built from `e2ec546`.
- `scripts/fetch-or-build-lib.sh --host-jni-only --lib-fixtures`: `JNI_PASS`,
  `KOTLIN_JNI_PASS`, `KOTLIN_STREAMS_PASS`, exit 0.
- `capabilities`: `abiVersion 3`, `libraryVersion 0.3.4`, 19 `operations`, `functions
  116`, `domainFunctions 108`; `describe`: 116 `functions`, 13 `streams`, 108 `domain` in
  29 modules (pinned in `NativeSmokeTest`, with the per-module counts of the 15 modules
  v0.3.4 added or extended). `classes.jar` has 63 classes (13 more `T3Api$Domain$<Module>`).
- `unzip -l` of the AAR: `assets/lib-t3-rust/i18n.json` (2837911 bytes, the lib's
  `data/i18n.json`: 20 locales, 1796 sources each). Gradle merges AAR assets into the app:
  `app-debug.apk` contains `assets/lib-t3-rust/i18n.json` with the same size, readable
  with `context.assets.open("lib-t3-rust/i18n.json")`.
- `git diff v0.3.3 v0.3.4` is empty for `src/stream.rs`, `src/bounded_snapshot.rs`,
  `src/reconnect.rs`, `src/composer.rs`, `src/projection.rs`, `src/pairing.rs`, `src/api/`, `src/ffi.rs`,
  `src/ffi_call.rs`, `include/`, `.github/` and `T3Client.kt`: every open finding about
  them stands.

Evidence names a test in this repository (`core/src/test/.../transport/DomainContractTest.kt`
unless said otherwise), all run through the host library; "describe only" means the
function is listed in `describe().domain` with its args but no test here calls it yet.

### Requests from the UI specification

| # | Request | Status | `domain` functions | Evidence |
|---|---|---|---|---|
| U0 | Timeline view | **Closed in v0.3.3** | `timeline.view`, `timeline.liveActivity` | `ThreadTimelineTest`, `ThreadSessionTest.eventsGoThroughTheLibInOneCallPerUpdate`, `u0LiveActivity` |
| U1 | Reduced thread view (phase 1 item 1) | **Closed in v0.3.3** | `projection.apply`, `timeline.view` | `ThreadTimelineTest.typesTheLibDoesNotKnowChangeNothing`, `ThreadSessionTest.threadVisitedOnlyMovesTheCursor` (cost: v0.3.3 finding 3, open) |
| U2 | `sidebar.view` over several environments | **Closed in v0.3.3** | `sidebar.view` (plus `status`, `metadata`, `detail`, `snapshot`, `command`, `threadAction`, `sortTimestamps`) | `SidebarViewTest`; the shell reducer half stays open (v0.3.3 finding 1) |
| U3 | `activity.describe` | **Closed in v0.3.3** | `activity.describe` | `u3ActivityDescribe` |
| U4 | `changes.summaries` | **Closed in v0.3.3** | `changes.summaries` | `u4ChangesSummaries` |
| U5 | Queue | **Closed in v0.3.3** | `queue.derive`, `queue.command` | `u5QueueDeriveAndCommand` |
| U6 | Approvals and questions | **Closed in v0.3.3** | `approvals.pending`, `approvals.command`, `questions.pending`, `questions.command` | `u6ApprovalsAndQuestions`, `ThreadTimelineTest.approvalsAndQuestionsBecomeBanners` |
| U7 | Models | **Closed in v0.3.3** | `models.catalog`, `runtimeModeCommand`; `preferences`, `favoritePayload` | `u7ModelsAndRuntimeMode`; `preferences`, `favoritePayload` describe only |
| U8 | Composer completions | **Closed in v0.3.3** | `composer.paths`, `fileLink`; `catalog`, `attachmentReference` | `u8ComposerCompletions`; `catalog`, `attachmentReference` describe only |
| U9 | `diffReview.present` | **Closed in v0.3.4** | `diffReview.present`; `scopedResult` | `u9DiffReviewPresent`; `scopedResult` describe only |
| U10 | Markdown as renderer-neutral blocks | **Closed in v0.3.3** | `markdown.blocks`, `markdown.link`, `codeBlocks.parse` | `u10MarkdownBlocksAndLinks`, `ThreadTimelineTest.blocksFlattenToPlainText` |
| U11 | `theme.tokens` | **Closed in v0.3.4** | `theme.tokens` (`colors` = the `colors.toml` text, optional `id`) | `u11ThemeTokensTokyoNight`, `u11ThemeTokensCatppuccinLatte` (Omarchy 4.0.2 files under `core/src/test/resources/omarchy/`: the 14 top-level keys, `appearance`, `roles.dim`, `surface`, `failed`) |
| U12 | `timeline.minimap` | **Closed in v0.3.4** | `timeline.minimap`, `timeline.minimapIndex` | `u12TimelineMinimap` |
| U13 | `subagents.buckets` and labels | **Closed in v0.3.4** | `subagents.buckets`; `describe`, `cards`, `fromThread` | `u13SubagentBuckets`; `describe`, `cards`, `fromThread` describe only |
| U14 | Usage and quotas | **Closed in v0.3.4** | `usageSummary.request`, `quotaSources.limits`; `usageSummary.window`, `aggregate`, `quotaSources.providers`, `sources`, `apply` | `u14UsageRequestAndQuotaLimits`; the rest describe only |
| U15 | `planWorkflow.view` / `followUp` | **Closed in v0.3.4** | `planWorkflow.view` (plans with `markdown.blocks` blocks); `followUp`, `attachSourceRef`, `implementationTitle`, `mode` | `u15PlanWorkflowView`; the rest describe only |
| U16 | ot3's string catalog in the lib or the AAR | **Closed in v0.3.4** | `i18n.t`, `i18n.resolve`, `i18n.catalog`; `i18n.languages`; AAR asset `lib-t3-rust/i18n.json` | `u16StringsInSpanishAndArabic` (`Close` → `Cerrar` / `إغلاق`, Arabic `rtl`, 1796 strings for `es`); `languages` describe only; the asset by `unzip -l` of the AAR and the APK |
| U17 | Pairing-link parser (phase 1 item 6) | **Closed in v0.3.3** | `connectionCatalog.pairingTarget` | `u17PairingTarget`; used by `PairingTargets` since UI-1f, `PairingTargetsTest` runs the 70-input table against it. |
| U18 | History paging (phase 1 item 7) | **Closed in v0.3.3** | `history.path`; `history.envelope` | `u18HistoryPath`; `envelope` describe only |
| U19 | `pullRequests.sidebarBadge`, `payload` | **Closed in v0.3.4** | `pullRequests.sidebarBadge`; `request`, `payload`, `threadCommand` | `u19PullRequestBadge`; the rest describe only |
| U20 | Branch picker | **Closed in v0.3.4** | `branchPicker.selectionTarget`; `dedupe`, `deriveLocal`, `sanitize`, `mismatch`, `temporaryBranch` | `u20BranchPickerSelectionTarget`; the rest describe only |
| U21 | Project identity | **Closed in v0.3.3** | `projects.identity`, `projects.monogram` | `u21ProjectIdentity`, `SidebarViewTest.rowsCarryKeysProjectsAndChips`, `NativeSmokeTest.domainRunsWithoutAConnection` |
| U22 | Git & changes sheet | **Closed in v0.3.4** | `gitControls.scope`, `present`, `buildAction` (confirmed actions as `steps [{tag, payload}]`); `resolveScripts`, `actions`, `scopedResult`, `buildVerifiedAction` | `u22GitControlsPresentAndConfirmedAction`; the rest describe only |
| U23 | Settings | **Closed in v0.3.4** | `nativeSettings.snapshot`, `scopedSettings.effective`; `nativeSettings.patch`, `description`, `modelSelection`, `idleCompaction`, `idleCompactionPatch`, `threadDefaults`, `scopedSettings.projectPatch`, `projectFile`, `validatedTargets`, `writePatch`, `writeWasDropped`, `targetView`, `aggregate`, `page`, `projectCatalogConfig` | `u23NativeAndScopedSettings` (also: no credential in the snapshot); the rest describe only |
| U24 | Checkpoint revert | **Closed in v0.3.4** | `checkpointRevert.safety`; `canPreview`, `reference`, `command`, `confirmedCommand`, `observation`, `recoveryMessage`, `appendPrompt` | `u24CheckpointRevertSafety`; the rest describe only |
| U25 | Tool inspector | **Closed in v0.3.4** | `toolInspector.request`; `compactReference`, `scope`, `parse` | `u25ToolInspectorRequest`; the rest describe only |

The functions before the semicolon in each row are the ones the tests call. Nothing in
the app calls a v0.3.4 function yet: UI-1 switches its string generator and theme
importer to them (DECISIONS.md, 2026-10-25), the later tranches use the rest.

### Phase 1 capabilities

| Item | Status at v0.3.4 | Evidence |
|---|---|---|
| 1. Apply thread events | **Closed in v0.3.3** | U0 and U1. |
| 2. Compact stream snapshots | Open (unchanged) | `src/stream.rs` and `src/bounded_snapshot.rs` unchanged; `compactTurnItems` stays false. |
| 3. Sidebar view and shell reducer | **Partially closed** (unchanged) | View closed (U2); no `shell.*` in `describe.domain`, `ShellReducer.kt` stays (v0.3.3 finding 1). |
| 4. Command builders | **Partially closed** (more builders) | v0.3.4 adds `pullRequests.threadCommand`, `checkpointRevert.command`, `gitControls.buildAction` and the `{method, payload}` of `pullRequests.request` / `payload` and `toolInspector.request`. Still missing: `message.dispatch`, `run.interrupt`, `projects.command` (v0.3.3 finding 2). |
| 5. Reconnect policy | Open (unchanged) | No `reconnect.*` in `describe.domain`; `src/reconnect.rs` unchanged; `Backoff.kt` stays. |
| 6. Pairing-link parser | **Closed in v0.3.3** | U17. |
| 7. History paging | **Closed in v0.3.3** | U18. |
| 8. Kotlin wrappers for the remaining operations | Open (unchanged) | `T3Client.kt` unchanged; `fetchDescriptor`, `pairBearer`, `boundedThread`, `requestOk` and the shell GET are still assembled in `NativeT3Transport`. |

### Earlier findings

| Finding | Status at v0.3.4 | Evidence |
|---|---|---|
| v0.3.2 transport 1: a shell metadata snapshot replaces the shell and moves the cursor | Open | `src/stream.rs` (`start_of`, `:52-62`) and `T3Client.kt` (`ThreadStream.poll`, `:297`) identical to v0.3.3. `ShellUpdates.kt` stays. |
| v0.3.2 transport 2: `rpc_rejected` without a machine-readable field | Open | `T3Exception(code, reason, requiredScope)` unchanged. |
| v0.3.0 5: `image()` throws `IllegalArgumentException` | Open | `requireNotNull(asset.bytes) { "missing_image_bytes" }` still at `T3Client.kt:107`. |
| v0.3.0 7: releases do not publish the host JNI library | Open | Release v0.3.4 assets: the AAR and its `.sha256` only; CI here still checks out the lib to build it. |
| v0.3.2 pairing 1: `pairing_rejected` also covers server errors | Open | `src/pairing.rs` identical to v0.3.3. |
| v0.3.2 pairing 2: re-pairing leaves the previous session alive | Open | No `auth.*` session operation; `src/ffi.rs` and the API registry unchanged. |
| v0.3.3 1: no shell reducer in `domain` | Open | No `shell.*` among the 108 entries. |
| v0.3.3 2: no `message.dispatch` or `run.interrupt` builder | Open | `composer.*` unchanged; `planWorkflow.attachSourceRef` decorates a ready command. |
| v0.3.3 3: the projection crosses JNI twice per update | Open | `projection.apply` / `timeline.view` signatures unchanged; no handle. |

## 2026-10-25: live Omarchy theme

UI_SPEC 3.1.3 reads the host's `~/.local/state/omarchy/current/theme/colors.toml` and
`theme.name` through T3. It works with v0.3.4 (DECISIONS.md, "Live Omarchy theme,
implementation"), with one workaround and one wrong field. Evidence: PROTOCOL_NOTES.md,
"Live Omarchy theme".

### 1. No typed way to get HOME or to read a `~` path

- **Expected (UI_SPEC):** HOME from `filesystem.getMetadata`, which expands `~`.
- **Live:** `call("filesystem.getMetadata", {paths: ["~"]})` → `{entries: [{kind:
  "directory"}]}`: no path. `call("projects.readFile", {cwd: "~", relativePath:
  ".local/state/omarchy/current/theme/colors.toml", allowMissing: true})` →
  `rpc_rejected`. `projects.readFile` with an absolute HOME as `cwd` works.
- **Workaround in the app:** HOME = `filesystem.browse({partialPath:
  "~/.local/state/omarchy/current/theme"}).parentPath` minus
  `/.local/state/omarchy/current`: two extra calls per environment (getMetadata for
  existence, since `browse` fails on a missing directory), and string surgery on a path.
- **Wanted (any one):** `filesystem.readFile({path, allowMissing?})` that expands `~`
  like `getMetadata` (`{contents, byteLength, truncated, exists}`, scope
  `filesystem:read`); or `getMetadata` entries with the resolved `path`; or a typed
  `server.omarchyTheme()` → `{name, colors} | null` that reads both files in one call.

### 2. `requiredScope` names the token's scope, not the missing one

- **Live:** with a token holding only `orchestration:read`,
  `call("filesystem.getMetadata", {paths: ["~"]})` and `call("projects.readFile", …)`
  throw `T3Exception(code = "write_not_authorized", reason = "The authenticated token is
  missing required scope: filesystem:read.", requiredScope = "orchestration:read")`.
- **Wanted:** `requiredScope = "filesystem:read"` (the scope the server says is missing);
  and, for reads, a code that does not say "write" (e.g. `not_authorized`), or a note in
  ANDROID.md that `write_not_authorized` covers reads too. The app falls back to the
  function's `describe` scope.

## 2026-10-25: UI-1f, after moving to `sidebar.view`, `theme.tokens` and `connectionCatalog.pairingTarget`

What the app still derives in Kotlin after UI-1f, and what would remove it.

### 1. `theme.tokens`: Material containers (nice to have)

- Proposed: two more `roles` (and `layers`) in `theme.tokens`: `accentContainer` (accent
  @ 0.14) and `failedContainer` (`failed` @ 0.14), with Qt's blend like every other role.
- Why: Android maps them onto Material's `primaryContainer`, `secondaryContainer` (the
  navigation bar indicator) and `errorContainer` (tokens.md section 2). ot3 has no such
  role, so `theme.tokens` does not return them and `Ot3Colors` blends them with straight
  alpha (`Ot3Blend`), the only colour the app computes. The chip fills
  (`projectFills`) already have accent @ 0.14 for themes whose accent is a palette slot,
  but not for hackerman and kanagawa, so they cannot stand in.
- In Rust today: `theme::drawn` and `LAYERS` (`src/theme.rs:20-37`, `:139`).

### 2. Thread menu rules and snooze presets (nice to have)

- Proposed: the thread menu's items with their visibility and enablement for a row and a
  capability set (`sidebar.threadAction` builds the command, not the menu), and ot3's
  snooze presets for a `now` and a time zone.
- Why: `core/home/ThreadActions.kt` keeps ot3's menu rules (`T3ThreadRow.qml:378-410`) and
  preset times (`T3ThreadRow.qml:104-110`) in Kotlin. They are presentation rules ot3
  writes in QML, not protocol, so they are listed here rather than moved silently.


## 2026-10-25: UI-1h host metadata audit

`sidebar.view.environmentName` is the caller-provided environment display name,
not host OS or a machine type (`src/sidebar/view.rs:136`). Android now shows that
label and the shell's `branch` on all real rows, plus the lib's `pullRequestBadge`.
No blocker for these fields. For parity with desktop machine glyphs, expose an
optional authoritative host/machine presentation field through the shared contract;
until available Android omits the glyph instead of guessing from label/URL.
See `docs/ui/compare/ui-1h/METADATA.md`. No Kotlin protocol implementation added.

## 2026-10-25: usage limits and routes

Two requests from the UI specification's motion, usage and OmaBridge requirements
(UI_SPEC 4.2.1, 4.10, section 6 rows U26 and U27), checked against v0.3.4 (`e2ec546`):
`describe().domain` and `src/`. Neither blocks a tranche: the screens ship a reduced form
until they land (UI_SPEC rule 6). Live limits themselves need nothing new:
`server.subscribeServerConfig` with `usageLimitSources: true` already carries them
(`bindings/android/describe.json:5710,6037`) and `quotaSources.apply` folds the stream
(`:1267`).

### U26. `quotaSources.present`: ot3's pooled limits (nice to have)

- What: the quota strip and the Limits tab show, per provider, one bar per window with a
  segment per account pooled across environments, the remaining percent, the pace, an
  expected-remaining marker, the earliest reset as "resets in 2h 5m", and notices
  ("Access denied", "Disconnected; readings may be outdated", "Could not refresh
  limits", external usage links). ot3 computes all of it in JavaScript ported from T3's
  `packages/shared/src/usageLimits.ts` (ot3 `ui/js/UsageLimits.js:5-43`,
  `ui/js/NativeUsageLimits.js:153-567`, provenance `ui/js/NativeUsageLimits.sources.json:2`);
  T3 Mobile uses the same helpers (`apps/mobile/src/features/usage/UsageLimitsPooled.tsx:302-309`).
- Why Android needs it: it is derived presentation of protocol data (account identity,
  workspace disambiguation, fresh-observation choice, pooling, pace); porting 600 lines of
  it to Kotlin breaks the architecture rule and drifts from ot3.
- Evidence: the seven usage functions in `describe().domain` are `usageSummary.window`,
  `request`, `aggregate` and `quotaSources.limits`, `providers`, `sources`, `apply`
  (`describe.json:1163-1267`); none pools or computes pace (`grep -n
  'pool\|pace\|elapsed\|resets in' src/quota_sources.rs` finds nothing). `limits` also
  drops `unavailable.message` and `resetCredits` (`src/quota_sources.rs:23-74`).
- Proposed: `quotaSources.present {environments: [{id, name, connected, error?,
  refreshFailed?, state}], now}` where `state` is `quotaSources.apply`'s, returning ot3's
  `UsageLimits.present` shape:

  ```json
  {"providers":[{"provider":"codex","label":"Codex",
     "accounts":[{"key":"…","label":"…","plan":"pro","hosts":"node, laptop",
                  "windows":[{"label":"5 hours","availablePercent":62,"reset":"resets in 2h 5m"}]}],
     "windows":[{"id":"five_hour","kind":"session","label":"5 hours","availablePercent":62,
                 "pace":"ahead","expectedAvailable":71.5,
                 "segments":[{"key":"…","availablePercent":62,"expectedAvailable":71.5,"reset":"resets in 2h 5m"},null],
                 "nextReset":"resets in 2h 5m"}],
     "weekly":{"…":"same window object or null"},"shortWindow":{"…":"same or null"}}],
   "notices":["node: Disconnected; readings may be outdated"],
   "external":[{"label":"ChatGPT usage","url":"https://chatgpt.com/#settings/Usage"}]}
  ```

  English source strings in `reset` and `notices`, so the app translates them with
  `i18n.t` (the reset should come as `{key, args}` or as `resetsAt` plus a separate
  `i18n` key if the catalog gains "resets in {time}"). Driver labels from ot3's map
  (`ui/js/UsageLimits.js:38-41`).

### U27. Routes of an environment: `routeKind`, `routePlan`, route hint (nice to have)

- What: an environment can be saved with several addresses (public, local network,
  Tailscale, an OmaBridge gateway); the app shows the route in use and falls back when
  one is unreachable (UI_SPEC 4.2.1).
- Why Android needs it: classifying an address and ordering attempts are connection
  policy; ot3 classifies host addresses in Rust (`src/connection_network.rs:48-54`:
  loopback "This machine", `100.` "Tailscale", other private "Local network", "Tailscale
  HTTPS") and the lib already groups and orders routes (`src/connection_catalog.rs:73-212`),
  Rust-only.
- Evidence: `connectionCatalog.pairingTarget` is the only `connectionCatalog` function in
  `describe().domain` (`describe.json:733-749`); `view`, `mutate` (`prefer-route`,
  `remove-route`) and `register` are not exposed; nothing reports a route in use or
  classifies an address; `grep -rni omabridge src` is empty.
- Proposed:
  - `connectionCatalog.routeKind {url, hint?}` → `{kind: "public"|"lan"|"tailscale"|"omabridge"|"loopback", reason}`
    (`hint` from the link; `lan` for RFC 1918, link-local, `.local` and `.lan`;
    `tailscale` for `100.64.0.0/10` and `*.ts.net`).
  - `connectionCatalog.routePlan {routes: [{id, kind, url, order, lastOkAt?, lastOkNetwork?}], network: {type: "wifi"|"cellular"|"ethernet"|"vpn"|"none", id?}, failures?: [{id, error, at}]}`
    → `{attempts: [{id, timeoutMs}], retryAfterMs?}`: the route that last worked on this
    network first, then the user's order; skip `lan` on cellular; never fall back after
    `unauthorized` or `t3_environment_mismatch`.
  - `connectionCatalog.pairingTarget` also returns `routeHint` when the link carries
    `route=omabridge` (and `gateway=<name>`), the format proposed in
    [OMABRIDGE_REQUESTS.md](OMABRIDGE_REQUESTS.md) OB4.
  - Optionally expose `connectionCatalog.view` / `mutate` for apps that keep T3's catalog
    document; ot3-Android keeps its own Room table (UI_SPEC 4.2.1), so the two functions
    above are what it uses.

## 2026-10-26: UI-N thread awareness (notifications and Live Updates)

Checked against v0.3.4 (`e2ec546`, the pinned AAR) and the companion branch
`feat/bridge-catalog-sync` (`027a025`): `git grep -i 'notif\|attention\|awareness'` over
`src/` finds only `reconnect::notify` (network wake-ups, `src/reconnect.rs:89-103`). What
exists and is enough for counts: `sidebar.view` returns `counts`
`{working, done, waiting, failed}` (`src/sidebar/view.rs:349-358`, ot3's bar semantics,
`ot3:ui/js/SidebarModel.js:45-49`) and each row's `pending`, `reason`, `revision`,
`detail` (`approval` / `input` / `plan` / ...) and `state` (`src/sidebar/view.rs:21-90,
193-245`). What does not exist: any notion of a transition, a seen baseline, an alert,
an expiry or a card. UI_SPEC 4.14 needs them; Kotlin must not derive them (ARCHITECTURE
layer 1, UI_SPEC rule 6), so UI-N's runtime waits for this request.

### U28. `awareness.view`: phases, card, alerts and dedupe (blocking UI-N runtime)

- What: one pure domain function the follow service calls on every shell change (and on
  a deadline tick), shared by ot3 (desktop alerts and bar) and Android.
- Why in the lib: three implementations of the same rules exist today, none reachable
  from Kotlin: T3's phases (`t3:packages/shared/src/agentAwareness.ts:59-135`), T3's
  transition selection and lifetimes (`t3:infra/relay/src/agentActivity/agentActivityAlerts.ts:14-161`,
  `agentActivityPayloads.ts:18-35`, `agentActivityAggregate.ts:59`, `fcmPayloads.ts:8-48`)
  and ot3's seen-state diff (`ot3:src/main.rs:54-144`, keyed by `sidebar.view`'s
  `revision`, first observation per environment silent through `@initialized`).
- Proposed call:
  `awareness.view {environments: [{id, name, shell?, state?}], now, baseline?, onScreen?: {environmentId, threadId}, preferences?: {approval, input, completion, failure}, lifecycle?: "cold"|"resumed"|"running"}`
  (same `environments` shape as `sidebar.view`; `state` non-null while not connected, so
  the environment's rows are neither withdrawn nor replayed).
- Proposed result:
  - `counts` — identical to `sidebar.view` `counts`.
  - `rows` — per non-subagent thread with a phase: `{route: {environmentId, threadId}, phase, title, project, host, status, since?, expiresAt}`, priority order (approval/input, failed, running, completed). `phase` is T3's seven-value set; `status` is the i18n source string resolved like `statusLabel`. `since` only when a stable phase-entry time exists (T3 hides its timer because `updatedAt` moves, `t3m:.../AgentActivityPresentation.kt:160-162`).
  - `card` — `{active, phase, title, subtitle, status, chip?: {kind: "working"|"live"|"approve"|"answer", count}, since?, expiresAt, route, rows ≤ 5}` or null. `expiresAt` absolute: running 2 h, waiting 24 h, finished 15 min after the newest result; never extended by a replay.
  - `alerts` — `[{id, group: "attention"|"finished", phase, title, body, route, expiresAt?}]`, already deduplicated: `id` stable over retries (`[environmentId, threadId, phase, revision]`); attention on entering approval/input/plan-ready, finished on entering completed/failed and only if fresh (≤ 2 min, T3); none for an environment without a baseline entry, none for `onScreen` (consumed into the baseline, as T3 `AgentNotifications.kt:148-161`), none for settled or snoozed threads.
  - `summaries` — `[{group, title, lines, route}]` when a group has several alerts ("2 agents need attention" / "2 agents finished" as catalog strings).
  - `baseline` — opaque JSON the host stores and passes back; bounded (drops threads that left every shell); version field so a lib upgrade can reset it silently.
  - `nextDeadline` — the earliest `expiresAt`, so the host can schedule one tick instead of polling.
- Decision for the lib owner: ot3 alerts on `pending` + `revision` (also ready plans and
  "review" completions); T3 alerts on phase transitions. Proposed: ot3's key (already in
  `sidebar.view`) with T3's groups, freshness and lifetimes. Either way the rule lives
  here, with fixtures shared by ot3 and Android.
- Tests to include: cold start (no baseline → no alerts), reconnect after outage (no
  replay), retry with same input (no duplicate), on-screen suppression consumed, two
  threads entering attention together (one summary), finished older than 2 min (no
  alert), settled/snoozed/subagent excluded, deadline arithmetic, revoked environment
  removed without a finished alert.
- Android consumes it through `NotificationContract` (`app/.../notifications/`), already
  parsing the proposed `card` and `alerts` shapes with fixtures; nothing calls the lib yet.

### UI2a client integration audit (2026-10-26)

U10 and U25 are consumed by the new native timeline reader: `timeline.view` blocks,
`toolInspector.request(reference, environment, threadId)` → typed `call` →
`toolInspector.parse(reference, environment, threadId, response)`. No new contract is
requested for Markdown, tool output, reasoning or file summaries. UI3's existing contracts
still need Android integration; this increment does not mark that UI complete.

U18's pure functions exist (`history.path`, `history.envelope`), but the current native
transport exposes `boundedThread(threadId)` without an authenticated page request. The
remaining transport request is `historyPage(threadId: String, cursor: String): JSONObject`,
returning the existing normalized `history.envelope` with its opaque next cursor. Android
needs it to merge only historical presentation rows without overwriting live control state,
and retain the visible id/offset. Until integrated there is an informative history boundary,
with no action that falsely appears to page. This is a transport request, not a request to
reimplement history parsing or eligibility in Kotlin. Root coordinates lib work separately.

### U28 update — delivered in lib v0.3.5 (2026-10-26)

The final native awareness.view is released; it is no longer an unimplemented lib
dependency. Android remains pinned to v0.3.4 for the UI-1 pre-release and will adopt
v0.3.5 immediately afterwards with the follow service and real native output fixtures.
The final contract adds locale, preferences.plan, alert.status and summary.body. Optional
fields are omitted, cold reseeds without alerts, plan-ready attention has waiting_for_input,
finished deadlines use latestRunCompletedAt, and nextDeadline includes snooze expiry.
See the lib v0.3.5 README for the full authoritative contract. No Kotlin state/count
logic is introduced to bridge versions.

### U29. Permission picker projection and unadvertised-mode fallback

- Source: ot3 `7b5b1de`, `T3PermissionPicker.qml:11-21`: four presentation
  definitions, current-mode fallback, and provider-supported runtime modes.
- `models.catalog` returns provider `supportedRuntimeModes`; `models.runtimeModeCommand`
  validates/builds changes. Android renders the advertised choices and uses the native
  command plus durable outbox, without an optimistic current-mode change.
- Missing common contract: `models.permissionView {catalog, selection?, runtimeMode?}`
  returning current choice and ordered rows `{id,label,description,icon}`. In particular
  an empty/missing supported-mode array means all definitions in ot3. Android does not
  reproduce that domain fallback: it shows no unadvertised choices until Rust exposes it.
- This limits providers which omit the array; advertised-provider changes remain usable.
  It does not block composer/requests or awareness runtime work.

### U18 integration follow-up — historical presentation merge (2026-10-26)

Rechecked v0.3.5 and ot3 `T3Backend.qml:340-344,1733-1743`: `history.path` and
`history.envelope` normalize a page, but there is no common merge of older and recent
presentation rows. ot3 currently implements that rule in QML (new page replaces matching
older ids; recent rows always win; page cursor advances independently of stream cursor).
Android must not overwrite the live projection/control state with the historical envelope.
In addition to authenticated `historyPage`, request
`history.mergeView {older, recent, page?}` → `{older, timeline}` (or equivalent native
stateful helper) with deterministic duplicate precedence and row order. Android owns
request cancellation by connection generation, busy/error presentation and reading anchor;
Rust owns row merge semantics. Cover overlapping pages, newer streamed revisions, stale
page completion, cursor exhaustion and reconnect snapshots. No Kotlin merge added.

### Reader action eligibility (2026-10-26)

`T3Panel.qml:1657-1664` scans following rows until the next user prompt to identify
commentary versus the terminal assistant response. `timeline.view` currently gives
`streaming`, `forkRunId` and `canRewindMessage`, but no terminal-response/action visibility
flag. Request `timeline` row `commentary` or `showMessageActions`, derived in Rust with
T3's terminal-assistant semantics. Android can then hide intermediate copy/time/actions
and use the 8 dp commentary gap without repeating that scan. Streaming action hiding and
message layout are presentation-only and already implemented independently.

### U31. Shared PR page ordering/merge and authenticated diff read (2026-10-26)

Pinned v0.3.5 provides `pullRequests.request`, `sidebarBadge`, `payload` and typed
`pullRequests.list/detail/checks/activity`. The page recipe builds list filters and
opaque cursors; typed LIST additionally supports `projectId`/`projectIds`/`filters`.
Android consumes those documented inputs directly, with native typed validation.

Still missing: a shared `pullRequests.listView {pages}` (or stateful reducer) defining
cross-page duplicate precedence, updatedAt ordering and native row presentation. ot3
`7b5b1de`, `T3PullRequests.qml:48` replaces matching host/repository/number entries and
sorts newest first in QML. Android must not reproduce that domain rule. For now it
appends the returned pages verbatim with page-position UI keys, preserving server
order and possible repeated rows. Request fixtures for overlap/revision, multiple
projects/hosts, invalid dates and refreshed/replaced cursor sequences.

`pullRequests.request {action:"diff",reference,cursor?}` returns method `diff`, an
HTTP recipe for `/api/pull-requests/diff`, not a typed RPC. Android's pinned AAR and
`T3Transport` expose no authenticated PR diff read binding. Request a native
`pullRequestDiff(reference,cursor?)` returning the existing patch envelope and opaque
next cursor, with credentials/URL construction and bounds in Rust. Android can then
feed patches to the existing native diff renderer and expose Code/file navigation.
Until then Overview displays `changedFiles` and explicitly says patches unavailable;
there is no nonfunctional Code tab or invented RPC/HTTP URL builder.

This request does not block list, Overview, Checks or the available Conversation
comments/review threads. Full activity timeline projection (commits/reviews ordering),
relative dates and cross-page discussion pagination remain future common-view work.
No write, review, merge, label or thread-link actions are exposed in this increment.

## 2026-10-26 — U30: Android Markdown image binary contract (v0.3.5)

Reader attachment previews now consume `T3Client.image(id, name, mimeType)` unchanged.
The pinned v0.3.5 sources expose `Connection::media_image(thread, path)` in `src/lib.rs`,
but `src/ffi.rs:t3_client_image` only accepts attachment metadata (no `op`),
`assetBytes`, and `projectFavicon`. The Android `T3Client.kt` has no `mediaImage` or
Markdown descriptor resolver. `timeline.view` supplies `markdownImages` with opaque
`markdown-…` ids and `source`; these ids are not attachment ids.

Requested: a typed binary Android operation accepting the native Markdown descriptor
and thread scope, with source classification/resolution, relative workspace handling,
same-origin signed grant checks, MIME/magic validation and the image 10 MiB limit in
Rust. Reuse `media_image` internally where applicable; explicitly reject unsupported
remote sources. Do not require Android to derive resource tags or interpret paths/URLs.
Until released, Android retains Markdown image alt text and does not request its URLs
or pass descriptor ids to `image`. This is a binding/domain gap, not a failed live grant.

Cancellation limitation: v0.3.5's synchronous binary JNI call has no per-read cancellation
handle. UI cancellation discards the result and connection-generation guards prevent
stale publication; an in-flight read may occupy its native thread until its native
30-second GET/RPC deadlines. Request a cancellable binary read handle if immediate
network abortion is required. Android does not disconnect a shared transport to cancel
one thumbnail.

## 2026-10-26 — U32: Shared new-thread defaults and creation recipe

Tracked in https://github.com/avillagran/lib-t3-rust/issues/17. This is not a missing
launch transport: v0.3.5 exposes typed `orchestration.launchThread`, `thread.create`,
`models.catalog` and branch helpers. The missing common policy remains in ot3
`7b5b1de`: `T3SidebarActions.qml:84–89` resolves model/runtime defaults from source,
project, sibling, catalog and draft; `T3Backend.qml:beginNewThread` resolves explicit
root, automatic route and workspace preference/confirmation; `src/write.rs:228–235`
chooses metadata creation versus workspace launch before typed validation/persistence.

Request a shared creation view/defaults from authorized projects/environments,
catalogs, source context, preferences and query, plus a request recipe from explicit
choices and caller-persisted ids. It should expose missing choices/confirmation
reasons and preserve durable retries, coordinating initial-message and plan follow-up
with the existing composer builder request. Reuse existing typed operations rather
than introduce another transport. Android owns editing, navigation, confirmation
and durable storage; it must not port fallback/search/routing rules to Kotlin.

Update 2026-10-27: Android now implements a bounded explicit project/model/current-checkout
flow through typed launch and durable retries. Full source/sibling defaults and shared
search/routing/worktree recipes remain open; no policy was copied as a fallback.

The remaining UI_SPEC §4.7 policy is still incomplete; this request is not
acceptance of that flow. No library checkout or production configuration was changed.

### U33 — common composer edit transforms (does not block UI-N)

Own-lib [issue18](https://github.com/avillagran/lib-t3-rust/issues/18) requests recall
candidates, completion trigger/replacement and list continuation from ot3 `7b5b1de`
T3Composer.qml:131,139–153,194–219. Existing035 `composer.paths/catalog/fileLink/
attachmentReference` and timeline `recallableText` do not expose these policies.
Define selection/replacement offsets as UTF-16 code units (or explicit conversion)
for Compose/QML, including astral/combining text and stale ranges. Android owns
selection, IME locks and scratch recall UI; it must not port QML regex/domain rules.

## 2026-10-26 — U34: Bounded provider registry icon bytes

Checked against v0.3.5 `src/models.rs` and binary operations: `models.catalog`
already validates and supplies `registryIcon`, `driverKind` and `instanceId`.
Android can render bundled desktop provider marks from exact driver identity,
with the desktop ACP mark for custom/unknown providers. It must not infer identity
from model names or instance display names.

Missing for complete registry parity: a native bounded/cancellable icon byte read,
for example `models.registryIconBytes(catalogProvider) -> {mimeType,bytes}` via the
binary ABI. Reuse catalog URL validation; allow only its official registry source,
validate redirects/content/size, no bearer credentials, and expose cancellation.
The Android consumer renders/caches returned bytes and retains the bundled fallback
while loading or on error. The current attachment image API cannot fetch this URL.
No Kotlin HTTP/protocol implementation or library checkout change is introduced.

## 2026-10-26 — U18 follow-up: Historical page integration into a live cached view

U18's `history.path` and `history.envelope` are available in v0.3.5; this is not a
request to reintroduce those primitives. Checked `src/history.rs` and
`src/domain/mod.rs` at the tag: the envelope converts page presentation rows,
while `projection.apply` consumes orchestration events. There is no shared
history-page merge operation for the live projection/view.

The thread cache therefore persists the exact current bounded projection and its
opaque cursor/history fields. Older history remains explicitly outside that window;
Android does not fabricate events from historical rows or merge/order/dedupe them
in Kotlin. Request a native `history.merge(projection, pageEnvelope, historyState)`
(or a separate common paged-view state) returning the presentation state and next
cursor/boundary metadata. Preserve current live controls, validate source thread ids,
and cover overlapping/replayed pages, concurrent streaming revisions and missing
ranges with shared fixtures. No protocol or library checkout was changed here.

### U35. Reader reference routing and scoped document read (2026-10-27)

Tracked in [lib issue19](https://github.com/avillagran/lib-t3-rust/issues/19).
The pinned v0.3.5 `markdown.link` classifies destinations but returns the original
href without resolving thread/environment or file paths. Desktop
`T3Backend.qml:1100–1113` resolves thread references against connected environments
(prefer the selected environment), decodes file paths and strips location suffixes
before its scoped document-open. Android currently opens HTTP(S) only.

Request native `reader.reference {href, environmentId, threadId, environments}`
(or equivalent) returning a tagged external URL, thread route, scoped document
resource or anchor/unsupported result. Rust owns destination validation, environment
selection and path normalization; Android owns navigation/loading/error UI. Never
expand Android HOME for a remote path. Define shared behavior where classification
and desktop routing differ, including colon suffixes and malformed encodings.

`documents::resource` and `Connection::document` already exist in Rust; expose the
bounded authenticated document read through Android/JNI instead of constructing
signed asset URLs in Kotlin. Coordinate image bytes with U30. Tests must cover
mixed-case web schemes, encoded/duplicate/missing thread IDs, remote/offline hosts,
relative/absolute/file paths, spaces/unicode/location suffixes, unsafe schemes,
malformed encoding, read bounds and stale results after environment changes.
No Kotlin reference parser or shared-library checkout change was made. This remains
an open reader parity requirement, not acceptance of file/thread navigation.

### U36. Shared live command-output projection (2026-10-27)

Tracked in [lib issue22](https://github.com/avillagran/lib-t3-rust/issues/22).

The pinned035 registry exposes `orchestration.subscribeCommandOutput {threadId,
itemId}` and Rust stream handling recognizes replace frames. It does not expose
the desktop command-output presentation reducer. Desktop currently implements
append/replace, bounded tail, ANSI/VT removal, carriage-return overwrite, running
and truncation in `omarchy-t3-code-ref/src/command_output.rs:13–112`. Android must
not reproduce those protocol/presentation rules in Kotlin.

Request `commandOutput.apply {state?, frames, closed?, error?}` returning opaque
state plus `{text,running,truncated,error?}` (or equivalent typed helper), together
with native request validation for the inspector reference. Preserve UTF-8
boundaries at the64KiB displayed tail, including the final plain-text trim; reset
on replace, preserve terminal overwrite semantics across frame boundaries, and
cover escape sequences split across chunks, stale reference replacement,
terminal close/error, empty output and oversized multibyte text. Desktop's
`shown[shown.len()-LIMIT..]` final trim is byte-indexed and needs a boundary-safe
contract rather than copying it. Android owns a single cancellable stream while
Follow is enabled and closes it on Stop, inspector dismissal or owner change.

No fabricated polling or Kotlin stream parser was introduced. Explicit inspector
reads remain useful independently; live output remains an open acceptance item.

### 2026-10-31 — U30 existing binary-channel implementation

The v0.3.5 Android `assetBytes(resource,maxBytes,timeoutMs)` binding already accepts the
same `media-file` resource used internally by `Connection::media_image`. Android now
uses it with thread scope and a 10 MiB limit; source adapter and image MIME/signature
checks match Desktop. See [HOST_MARKDOWN_IMAGES.md](HOST_MARKDOWN_IMAGES.md).
The dedicated typed Markdown API remains a desirable consolidation: add
`T3Client.markdownImage(threadId,descriptor): T3Asset` and a `markdownImage` branch in
`ffi.rs:t3_client_image`, moving Desktop `workspace_path` and public/data handling into
Rust, returning bytes plus MIME/dimensions. Reuse `Connection::media_image` for host
sources, its exact thread grant, 10 MiB/40 MP limits, and credential-free bounded public
HTTP/data reads. No shared-library change is necessary for this implementation and no
shared checkout or pinned binary was modified. Native signed-grant tests are present,
but require the separately built host JNI library to execute on the JVM.

## P15 — Android terminal context/output helpers (AAR 0.3.6)

Verified against AAR SHA-256
`5e44c1ef846f27b7f131ca3bbacb771f21ce70028f061014904064e4caf9e21d` and
source `86819eb442ae4a8635cb21206f2195343c75b094`:

- terminal::context binding is absent in AAR 0.3.6.
- terminal::Output binding is absent in AAR 0.3.6.
- `T3Api.Terminal.open/write/resize/attach` **are present**. No replacement RPC,
  REST endpoint or Kotlin protocol implementation is requested.

Expose the existing Rust helpers through the Android domain/execute interface:
context from shell/thread/fallback and a bounded output projection preserving
snapshot replacement, cursor deduplication, cleared history and exited/error
status. Publish fixtures for worktree/project/scratch cwd, provider instance,
invalid/missing context, initial snapshot, duplicate/out-of-order output, Unicode,
clear, exit and reconnect without restart. Keep renderer/keyboard code on Android.

Separate server requirement: the current `terminal.attach` contract demands
`terminal:operate` and may launch a process. It cannot fulfill a read-only token
terminal view. Add an authorized observation-only subscription that cannot start,
restart, write or resize a shell; expose it in the typed Android bindings. Never
borrow the Desktop owner's session for mobile observation. P15 remains blocked;
see `docs/verification/p15-terminal.md` for the reachable diagnostic and acceptance
items that remain unimplemented.
