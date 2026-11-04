# KnowledgeLauncher / ShreddedPaper fork

Upstream: https://github.com/GrimAnticheat/Grim, branch `2.0`, starting at `41f6f6e`.
The upstream GPL-3.0 licence and history are preserved. This checkout is maintained locally at the owner's request.

## First-pass scope

- Native Java 1.21.11 on the Project Evangelium ShreddedPaper fork uses Grim's existing checks and Folia scheduler integration.
- Standalone Geyser clients receive the same exemption policy as upstream Geyser/Floodgate users. No Java movement simulation is applied to authenticated Bedrock sessions.
- Other Java versions translated on the launcher are not validated by this first pass. Upstream explicitly does not support ViaVersion on a proxy.
- No Java checks are disabled based on ping or client name. Movement, reach and timer thresholds remain upstream values.

## Authenticated Bedrock forwarding

Requires the matching launcher change `04f55e57` (or a later build containing it).
The launcher removes any incoming `evangelium:bedrock` profile properties and adds exactly one `v1` property only for its trusted Geyser connection type. The entire modern Velocity forwarding payload is HMAC-signed with the backend secret.

The fork honors that marker only when the backend's `launcher.licensed` flag is set by the verified launcher. Missing, unsupported or duplicate markers do not grant this exemption. Existing upstream Floodgate/Geyser detection is retained. Usernames and UUID formats are not used for the new exemption.

## Latency task queue

The compensation queue is ordered by transaction ID, preserving FIFO within a transaction. This prevents an older acknowledged update from remaining behind a future update and ensures acknowledged state is applied in transaction order. Acknowledgements are rechecked while holding the insertion lock, closing the race where a reply arrived between the old fast check and queue insertion.

Tasks still wait for their original acknowledgement. This does not add movement tolerance, permit extra ticks or change Timer/Simulation thresholds. It addresses reproduced queue defects; it does not establish that every reported high-ping violation has the same cause.

## Build on Java 21

```powershell
.\gradlew.bat :common:test :bukkit:shadowJar --configure-on-demand --no-configuration-cache -PmavenLocalOverride=false
```

Configuring only the requested Bukkit/shared projects avoids the unrelated Fabric Loom requirement for Java 25. The default shaded build includes its matching PacketEvents version and relocates dependencies. Output is in `bukkit/build/libs/`.

## Validation

- Shared tests: 54 passed, including Bedrock marker validation and four deterministic latency-queue regressions.
- Matching launcher: 167 tests passed, including signed forwarding contents, conflicting-marker removal and skin-property preservation.
- Local native protocol test: 17.22-block walk and backend restart passed; Java and malformed-marker sessions remained tracked, valid-marker sessions were exempt, and there were no Simulation/Timer violations.
- Controlled network-latency test: measured 615–701 ms transaction ping with jitter and a 250 ms upstream spike. A 16.79-block walk produced no Simulation or Timer violations. The delayed restart transferred sessions without disconnecting, but both Java bots then received one GroundSpoof violation; that restart case remains unresolved and is not a clean pass.

These use protocol bots and a local-only profile fixture. They are not physical Bedrock-device or stock Java-client tests. The fixture records all violations; the bot's known yaw-wrapping `AimModulo360` alert is separately identified and is not treated as a movement/Timer success criterion. No related production check is disabled.

The unified AC integration now bundles AC.jar in KnowledgeLauncher when anticheat=true. The diagnostic probe stays test-only. Publishing a tested launcher release, deploying the updated Chronos backend, and installing the VPS/router route are separate deployment steps.

## Post/use-item report

The owner supplied `SirGrem failed Post (x20) use item v1.21.11`. The item and client distribution are unknown. The existing movement harness does not exercise item use and did not reproduce this flag. No Post exemption or detection change has been made.

Upstream issue [2759](https://github.com/GrimAnticheat/Grim/issues/2759) reports the same alert for tridents. Its discussion ended after the maintainer advised using a vanilla client and the reporter thanked them. This is a diagnostic lead, not proof that this owner's report has the same cause. A stock Java 1.21.11 comparison or a reproducible packet trace is still needed. High latency by itself does not reorder bytes on the TCP connection; none of the completed tests establishes a cause for this specific alert.


## Unified AC

See docs/superpowers/specs/2026-11-04-unified-ac-design.md. One AC plugin combines our movement fork and remote combat adapter with independent switches, single Evangelium licensing, isolated customer state, and tested key-server updates. The shaded build verifies gRPC DNS and pick_first provider descriptors before accepting AC.jar.
