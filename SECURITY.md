# Security and privacy

## Local access
Password-protected vaults use AES-256-GCM, fresh 96-bit nonces per save, PBKDF2-HMAC-SHA256 with 600,000 iterations and a random 128-bit salt. Passwords require 12 characters and are not stored. Wrong-password and authentication failures do not overwrite the vault. File writes use atomic replacement and a process lock.

Password-free mode uses the same encrypted format with a random secret stored beside the vault in `.local-key`. This is convenience, not protection from someone who can read the local files. On POSIX systems the key and vault use owner-only file permissions; other systems rely on their filesystem access controls. Protect and back up the key and vault together. An unlocked app or compromised OS is outside the vault’s protection.

Schema 3 includes tasks and habit history. Schema 1 and 2 are read and upgraded on save, preserving the original encrypted file as `.v1.bak` or `.v2.bak`. Never overwrite a conflicting backup automatically. Old app versions cannot open newer schemas.

## Updates
Settings → Updates reaches the network only when **Check for updates** is pressed. It sends one request to `https://api.github.com/repos/Rabadakku/Yoru/releases/latest`, carrying a User-Agent and nothing about the person or their vault. Installers are downloaded only from `https://github.com/Rabadakku/Yoru/releases/download/`, written to a temporary file, and kept only when their size and SHA-256 match the digest GitHub publishes for that release; a missing or mismatched checksum deletes the file. A copy built from source is never replaced.

On macOS the verified disk image is mounted read-only, the app is copied out and checked to be `dev.yoru` at the expected version, and after Yoru closes its vault a shell script swaps the bundles, restoring the old one if the new one cannot be moved in. On Windows the verified `.msi` runs after Yoru quits. On Linux Yoru only verifies the `.deb`; installing it needs an administrator and is left to the person. Releases are not code-signed, so the checksum proves the file is the one published on GitHub, not who built it.

## AI assistants
Yoru calls no AI service and holds no AI key. With **Settings → Integrations · AI assistants** switched on, an AI app on the same computer — Claude Desktop, Claude Code or another Model Context Protocol client — can start `Yoru --mcp`, which passes tool calls to the running, unlocked app. Both switches are off by default and kept in this computer's preferences.

- **Who can connect.** The running app listens on a Unix-domain socket in `~/Yoru/assistant/`, a folder created with owner-only permissions on POSIX systems. Every request must carry a 256-bit random token read from that folder; it is replaced each time the app starts and deleted, with the socket, when assistants are switched off or the vault closes. There is no TCP port. On Windows the folder relies on the user profile's access controls.
- **What leaves the computer.** Yoru sends nothing itself. Whatever an assistant reads through Yoru's tools becomes part of your conversation with that assistant, and its app sends it to its provider under that provider's terms, just as anything you paste into it would be.
- **What an assistant can do.** Reading tools cover tasks, pages, the schedule, tracked time and habits. Changing tools, behind a second switch, add and edit tasks, pages, plans, time and habit check-ins through the same validation as the interface. There are no tools that delete. The vault is backed up before an assistant's first change and at most every fifteen minutes of changes after that, and the app lists recent assistant changes in Settings.
- **Untrusted text.** Page and task text is returned as data. The server's instructions tell the assistant never to follow instructions found in the vault, and more importantly the tools themselves only do what their arguments ask: nothing in the vault can widen what a tool does or turn a read into a write. Tests include hostile text.
- **Claude Desktop's settings.** **Add Yoru to Claude Desktop** edits Claude Desktop's settings file only when pressed, keeps every other entry, writes a copy of the file as it was beside it, and leaves a file it cannot parse untouched.

Tasks can also still be proposed by an AI chat you use yourself: Yoru shows a prompt to paste into it and reads back the reply you paste in. That reply is untrusted text. It must pass validation and your review before one transactional import, and it never changes existing tasks or habit records.

## Limits
Exports are plaintext. Do not commit vaults, keys, exports, backups or class/health records. Arbitrary downloaded plugin execution is not supported. Habit check-offs record the user’s own entries, with no medication dosing logic or medical recommendations. The app has not undergone an independent security or accessibility assessment.

Report vulnerabilities privately to the repository owner, without personal records in public issues.
