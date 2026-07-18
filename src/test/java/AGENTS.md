# Test instructions

These rules refine the root `AGENTS.md` for code under `src/test/java`.

- Name tests after observable behaviour and requirement intent.
- Domain tests should prove invariants and invalid transitions without loading Spring.
- PostgreSQL-specific behaviour, constraints, locking, idempotency, and migrations require Testcontainers.
- Do not use H2 or mock the database for database-dependent behaviour.
- Include unhappy paths and recovery behaviour, not only successful examples.
- For concurrency tests, coordinate competing operations deliberately and assert the final database state, not merely HTTP responses.
- Keep Spring Modulith and architecture tests active as modules are added.
- A test must explain which risk it prevents; avoid tests that only mirror implementation details.
