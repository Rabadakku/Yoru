# Security and privacy

## Local access
Password-protected vaults use AES-256-GCM, fresh 96-bit nonces per save, PBKDF2-HMAC-SHA256 with 600,000 iterations and a random 128-bit salt. Passwords require 12 characters and are not stored. Wrong-password and authentication failures do not overwrite the vault. File writes use atomic replacement and a process lock.

Password-free mode uses the same encrypted format with a random secret stored beside the vault in `.local-key`. This is convenience, not protection from someone who can read the local files. On POSIX systems the key and vault use owner-only file permissions; other systems rely on their filesystem access controls. Protect and back up the key and vault together. An unlocked app or compromised OS is outside the vault’s protection.

Schema 3 includes tasks, collection and habit history. Schema 1 and 2 are read and upgraded on save, preserving the original encrypted file as `.v1.bak` or `.v2.bak`. Never overwrite a conflicting backup automatically. Old app versions cannot open newer schemas.

## Optional AI
Core tracking works offline. Only an explicit class-file extraction action calls `https://api.openai.com/v1/responses`. No telemetry or local HTTP listener. The user sees the selected filename and size and approves uploading its entire contents. API keys are entered per request and never persisted by Yoru. Request buffers can exist temporarily in process memory.

Requests use `store:false`, which is not a promise of zero provider retention. See [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data). Requests are size/time bounded, reject redirects, use strict structured output and expose no tools. File instructions are treated as untrusted data. Results must pass validation and user review before a transactional task import. No AI response automatically alters existing tasks or habit records.

## Limits
Exports are plaintext. Do not commit vaults, keys, exports, backups or class/health records. Provider adapters and arbitrary downloaded plugin execution are disabled. Habit check-offs record the user’s own entries, with no medication dosing logic or medical recommendations. The app has not undergone an independent security or accessibility assessment.

Report vulnerabilities privately to the repository owner, without personal records in public issues.
