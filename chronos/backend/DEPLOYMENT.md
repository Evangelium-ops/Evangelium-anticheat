# Launcher-aware Chronos backend

This module builds the existing remote engine plus the Evangelium launcher-license verifier. It keeps the existing TLS endpoint, private keys, database and configuration; do not replace those with test-fixture files.

Build from the Grim fork root:

```powershell
.\gradlew.bat :chronos:backend:test :chronos:backend:installDist :chronos:backend:distZip --configure-on-demand --no-configuration-cache -PmavenLocalOverride=false
```

The runtime is `build/install/backend/` under this module, and the ZIP is in `build/distributions/`. Its Dockerfile can replace the existing backend image:

```powershell
docker build -t anticheat-backend:ac .\chronos\backend
```

Deploy that image through the existing Chronos Kubernetes deployment while retaining its mounted TLS/legacy signing keys, database volume and config. The production container build/deployment has not been executed by this task. Existing standalone approved Chronos clients remain supported unless `allow-legacy-chronos: false` is set in the backend config. Unified AC uses only the Evangelium proof path and never asks for a separate approval code.

The existing VIP remains 192.168.50.75 TCP9443. The owner-installed router forward and VPS HAProxy route expose it through 198.251.88.40 TCP9443, with TLS passed through. The backend verifies the bundled public key-server verifier. No license-server private key or raw launcher license key belongs in the backend image.

If rolling back before this backend is installed, leave Combat=false in launcher.properties; Movement=true can run independently. The local integration fixture binds loopback only and is never in this runtime distribution.
