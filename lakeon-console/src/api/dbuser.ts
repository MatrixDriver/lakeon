import client from './client'

export interface DatabaseUser {
  id: string
  database_id: string
  username: string
  role: 'ADMIN' | 'WRITER' | 'READER'
  is_owner: boolean
  created_at: string
  updated_at: string
}

export interface DatabaseUserCreated extends DatabaseUser {
  password: string
}

export const dbuserApi = {
  createUser: (dbId: string, data: { username: string; role: string; password?: string }) =>
    client.post<DatabaseUserCreated>(`/databases/${dbId}/users`, data),

  listUsers: (dbId: string) =>
    client.get<DatabaseUser[]>(`/databases/${dbId}/users`),

  updateRole: (dbId: string, userId: string, data: { role: string }) =>
    client.put<DatabaseUser>(`/databases/${dbId}/users/${userId}/role`, data),

  deleteUser: (dbId: string, userId: string) =>
    client.delete(`/databases/${dbId}/users/${userId}`),

  resetPassword: (dbId: string, userId: string) =>
    client.post<{ password: string }>(`/databases/${dbId}/users/${userId}/reset-password`),
}
