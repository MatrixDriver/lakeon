import client from './client'

export const notebooksApi = {
  list: () => client.get('/datalake/notebooks'),
  create: (name: string, image: string) =>
    client.post('/datalake/notebooks', { name, image }),
  get: (id: string) => client.get(`/datalake/notebooks/${id}`),
  save: (id: string, content: string, version = false) =>
    client.put(`/datalake/notebooks/${id}${version ? '?version=true' : ''}`, content, {
      headers: { 'Content-Type': 'application/json' },
    }),
  rename: (id: string, name: string) =>
    client.patch(`/datalake/notebooks/${id}`, { name }),
  remove: (id: string) => client.delete(`/datalake/notebooks/${id}`),
  listVersions: (id: string) => client.get(`/datalake/notebooks/${id}/versions`),
  getVersion: (id: string, ts: string) =>
    client.get(`/datalake/notebooks/${id}/versions/${ts}`),
  restore: (id: string, ts: string) =>
    client.post(`/datalake/notebooks/${id}/versions/${ts}/restore`),
}
