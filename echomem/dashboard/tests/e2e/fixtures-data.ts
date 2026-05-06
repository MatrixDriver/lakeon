const BASE = 'http://127.0.0.1:8473'

export async function seedFixture() {
  for (let i = 0; i < 8; i++) {
    await fetch(BASE + '/memory/ingest', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        text: `seed memory #${i} about jacky and echomem dashboard work`,
        agent_id: 'cli', source_kind: 'explicit',
      }),
    }).then((r) => { if (!r.ok) throw new Error('seed failed') })
  }
  await new Promise((r) => setTimeout(r, 5_000))
}
