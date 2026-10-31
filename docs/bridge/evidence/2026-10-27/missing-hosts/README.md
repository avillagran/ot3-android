# Missing host diagnosis — 2026-10-27

The dedicated reporting method ran on the known Xiaomi with a plain Application,
without AppGraph/migrations/reconnection. It reported one independent environment
labelled VQ, UUID 8b55eaad-3149-443c-953f-afd6dd002666, zero bridge-managed rows and
no selected native bridge. This UUID matches NODE's live public descriptor at
127.0.0.1:8643. The test package was installed; the target APK/data were retained.

Two running bridges share the display name NODE. The runtime-tracked instance
uses omabridge-route and publishes T3+SSH to its approved peers. Its fixed 3773
argument is a fallback; --t3-runtime reads the current runtime port, so a failed
3773 probe is not a routing diagnosis. The older UI-test instance publishes SSH
only and knows a peer labelled mbp-m2. Labels do not establish endpoint identity.

Neither observation proves the native catalog publisher or downstream T3 authority
is configured for the phone. Pairing/host publication requires choosing the actual
bridge identity, a compatible catalog service, explicit per-client environment
allowlists and bounded per-host T3 authorization. Existing peer visibility does not
supply those grants. Do not merge the two stores, copy tokens or replace listeners
merely because the labels match. The current bounded enrollment policy is read-only;
write access for creating chats on newly managed hosts needs separate explicit support.

No productive bridge, grant, session, listener or publisher was changed. The app now
exposes its existing bridge-pairing entry point when no selected bridge is loaded;
that is onboarding clarity, not completed multi-host synchronization.

## Follow-up: public route identities and publisher preflight

The user clarified that MBP-M2 appears in ot3-Android. A second opt-in test,
`InstalledEnvironmentOriginReadOnlyTest`, ran successfully with the same plain
Application runner. It reconstructs origins without userinfo, path, query or
fragment and fetches public descriptors without authentication. The saved VQ
origin is the NODE public gateway on port 39670, and its descriptor matches the
saved UUID. See `origin-preflight.json`. This does not explain the reported
MBP-M2 label; device/profile correspondence still needs confirmation.

The public gateway on port 55944 identifies Omarchy/MBP-M2 as
`1dc39036-dea5-45d6-9c4e-646082c94529`. These are two distinct live T3 identities.
Historical vault evidence identifies `omabridge-route` as canonical NODE; the
UI-test state is a different identity and must not be merged into it.

The CLI built from the Android SDK source revision 6b7c04c exposes
`catalog-publish`; the installed NODE CLI does not. In a fresh temporary state,
a fixed SSH adapter read only the desktop's sanitized connections-view, with an
explicit local-primary UUID alias and both public routes. Publication verified
both descriptors and produced two environments/two endpoints, revision 1.
A repeated poll returned unchanged. No clients were authorized in this isolated
preflight; it does not prove phone enrollment, delegated issuance or productive
synchronization. See `publisher-preflight.json`.

Remaining operational steps: reconcile the reported phone/profile, preserve the
canonical bridge identity while upgrading its compatible server, bind the actual
paired mobile client to the environment allowlist, configure each owned T3 issuer
(and explicit downstream delegation for the remote host), then validate native
catalog import and bounded read-only enrollment on that phone. Peer visibility
alone is insufficient. New-chat write permission remains a separate capability.
No productive binaries, services, grants, sessions or policies were changed.
