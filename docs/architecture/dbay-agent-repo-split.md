# DBay Repo Split Boundary

## Lakeon Repo

`lakeon` owns Lakebase Core:

- Serverless PostgreSQL lifecycle
- Neon pageserver / safekeeper / proxy integration
- database / branch / version / backup / PITR
- SQL execution and connection metadata
- LakebaseFS folder / file / sync APIs
- tenant auth needed by Lakebase Core
- Lakebase Console at `dbay.cloud`

`lakeon` must not depend on `dbay-agent`.

## DBay Agent Repo

`dbay-agent` owns:

- DataAgent
- Sources / Connectors
- Knowledge Base
- Memory Base
- Datalake / Ray / Notebook / Pipeline
- Agent-facing Console
- Intelligence workers

`dbay-agent` depends on Lakebase only through stable HTTP APIs at `dbay.cloud`.

## Contract

- No shared RDS schema across repos.
- No importing Java packages across repos.
- No shared Kubernetes namespace.
- Cross-repo communication uses service tokens and documented HTTP APIs.

