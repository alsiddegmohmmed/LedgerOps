# Production Java instructions

These rules refine the root `AGENTS.md` for code under `src/main/java`.

- Put business invariants and state transitions in domain objects, not controllers, JPA entities, or repository adapters.
- Keep the domain package plain Java. Imports from Spring, Jakarta Persistence, HTTP libraries, and infrastructure packages are forbidden there.
- Treat repository interfaces as domain or application ports. Put Spring Data interfaces and JPA entities in `infrastructure`.
- Map domain objects to persistence objects explicitly. Do not annotate domain objects as JPA entities.
- Define transaction boundaries in application services or persistence adapters only when the current vertical slice requires them. Keep them narrow.
- Expose cross-module behaviour through published module interfaces; never query another module's tables.
- Model identifiers, money, currency, and other validated concepts as explicit value objects.
- Use constructor injection and an injected `Clock`. Avoid field injection and widespread `Instant.now()` calls.
- Make invalid states difficult to represent and reject invalid transitions without partially changing state.
- Add a database constraint whenever a critical invariant can be reinforced by PostgreSQL.
