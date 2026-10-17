# v0.1.0 implementation plan
## Contract
- Minecraft Java 1.21.1, Java 21, Fabric; standalone direct CHZZK receiver; dedicated and integrated server support.
- Admin-managed mapping: stable binding id -> player UUID + OBS widget source hash. No central service, remote config, OBS browser or client-reported amounts.
- Connect enabled bindings, accept only current live donation frames (93102, or 93101 item messageTypeCode/msgTypeCode 10); ignore history 5101/15101 and ordinary chat. Request no history. Fail closed for unknown money/identity.
- Public immutable Donation record and RECEIVED (real only), TEST (synthetic only) Fabric events on server thread. Not a currency/payment ledger. API consumers own effects and their transactional idempotency.
- Persistent per-world dedup bounded to 7 days/50k; offline events not dispatched; no catch-up on join. Direct client supports bounded payloads/timeouts/heartbeat/reconnect and closes with server stop. No absolute exactly-once guarantee across crash between recording and effect.
- Config default has no bindings; validated complete candidate before reload. UUID/source uniqueness. Operator commands status/reload/test with offline test target rejected.
- Delivery contains binary/sources/example consumer code/Korean docs/checksums. No source hashes or credentials.
## Work
1. Scaffold build and API contract.
2. Implement pure-Java receiver/parser/dedup with unit tests; implement Fabric binding/config/commands separately.
3. Build, tests, isolated dedicated+integrated smoke; mock upstream real-path event and duplicate tests; read-only real upstream handshake when feasible, never fabricate paid verification.
4. Independent spec review first, then quality review; fix and re-test.
5. Package exports and verify manifest/archive.
