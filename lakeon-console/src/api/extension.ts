import client from './client'

export interface ExtensionInfo {
  name: string
  category: string
  description: string
  installed_version: string | null
  installed: boolean
}

export interface ParameterInfo {
  name: string
  setting: string
  unit: string | null
  category: string
  description: string
  editable: boolean
  context: string
}

export const extensionApi = {
  list: (dbId: string) =>
    client.get<ExtensionInfo[]>(`/databases/${dbId}/extensions`),

  enable: (dbId: string, name: string) =>
    client.post(`/databases/${dbId}/extensions/${name}/enable`),

  disable: (dbId: string, name: string) =>
    client.post(`/databases/${dbId}/extensions/${name}/disable`),

  listParameters: (dbId: string) =>
    client.get<ParameterInfo[]>(`/databases/${dbId}/parameters`),

  updateParameter: (dbId: string, name: string, value: string) =>
    client.put(`/databases/${dbId}/parameters/${name}`, { value }),
}
