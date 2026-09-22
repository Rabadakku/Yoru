# Security and privacy

## Local access
Password-protected vaults use AES-256-GCM, fresh 96-bit nonces per save, PBKDF2-HMAC-SHA256 with 600,000 iterations and a random 128-bit salt. Passwords require 12 characters and are not stored. Wrong-password and authentication failures do not overwrite the vault. File writes use atomic replacement and a process lock.

Password-free mode uses the same encrypted format with a random secret stored beside the vault in `.local-key`. This is convenience, not protection from someone who can read the local files. On POSIX systems the key and vault use owner-only file permissions; other systems rely on their filesystem access controls. Protect and back up the key and vault together. An unlocked app or compromised OS is outside the vault’s protection.

Schema 3 includes tasks and habit history. Schema 1 and 2 are read and upgraded on save, preserving the original encrypted file as `.v1.bak` or `.v2.bak`. Never overwrite a conflicting backup automatically. Old app versions cannot open newer schemas.

## Updates
Settings → Updates reaches the network only when **Check for updates** is pressed. It sends one request to `https://api.github.com/repos/Rabadakku/Yoru/releases/latest`, carrying a User-Agent and nothing about the person or their vault. Installers are downloaded only from `https://github.com/Rabadakku/Yoru/releases/download/`, written to a temporary file, and kept only when their size and SHA-256 match the digest GitHub publishes for that release; a missing or mismatched checksum deletes the file. A copy built from source is never replaced.

On macOS the verified disk image is mounted read-only, the app is copied out and checked to be `dev.yoru` at the expected version, and after Yoru closes its vault a shell script swaps the bundles, restoring the old one if the new one cannot be moved in. On Windows the verified `.msi` runs after Yoru quits. On Linux Yoru only verifies the `.deb`; installing it needs an administrator and is left to the person. Releases are not code-signed, so the checksum proves the file is the one published on GitHub, not who built it.

## AI task proposals
Core tracking works offline, and **Check for updates** is the only thing that uses the network. No telemetry, no local HTTP listener, and no AI provider is contacted by any 1.0 build.

Tasks can be proposed by an AI chat you use yourself: Yoru shows a prompt to paste into it and reads back the reply you paste in. That reply is untrusted text. It must pass validation and your review before one transactional import, and it never changes existing tasks or habit records.

An API-backed class-file extraction existed before 1.0. Its code is kept for a later release, but no build can reach it; if it returns, this section will say what it sends and where.

## Limits
Exports are plaintext. Do not commit vaults, keys, exports, backups or class/health records. Provider adapters and arbitrary downloaded plugin execution are disabled. Habit check-offs record the user’s own entries, with no medication dosing logic or medical recommendations. The app has not undergone an independent security or accessibility assessment.

Report vulnerabilities privately to the repository owner, without personal records in public issues.
