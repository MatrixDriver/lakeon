# DBay Agent Cutover Checklist

## Before Cutover

- [ ] `lakeon` Lakebase core E2E passes.
- [ ] `dbay-agent` API health endpoint passes.
- [ ] `dbay-agent` can call Lakebase health endpoint.
- [ ] `dbay-agent` can validate a Lakebase tenant token.
- [ ] `dbay-agent` can create/read a LakebaseFS folder through API.
- [ ] `dbay-agent` can create/read a Lakebase database through API.
- [ ] Railway project for DBay Agent Console is live.
- [ ] DBay Agent CCE namespace is live.

## Cutover

- [ ] Hide Knowledge, Memory, DataAgent, Datalake, Sources from `dbay.cloud` Console.
- [ ] Add cross-link from `dbay.cloud` to DBay Agent Console only after DBay Agent login works.
- [ ] Remove intelligence workers from Lakebase CCE after DBay Agent E2E passes.

## After Cutover

- [ ] Run Lakebase core E2E against `dbay.cloud`.
- [ ] Run DBay Agent E2E against DBay Agent API.
- [ ] Verify `dbay.cloud` public Console only exposes Lakebase and FS.
- [ ] Verify DBay Agent Console can use existing Lakebase tenant identity.

