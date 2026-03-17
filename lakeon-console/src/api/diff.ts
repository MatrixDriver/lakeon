import client from './client'

export interface SchemaDiffResponse {
  tables: {
    added: TableInfo[]
    removed: TableInfo[]
    modified: TableModification[]
  }
  indexes: {
    added: IndexInfo[]
    removed: IndexInfo[]
  }
}

export interface TableInfo {
  name: string
  schema: string
  columns: ColumnInfo[]
}

export interface ColumnInfo {
  name: string
  data_type: string
  is_nullable: boolean
  column_default: string | null
}

export interface TableModification {
  name: string
  schema: string
  columns: {
    added: ColumnInfo[]
    removed: ColumnInfo[]
    modified: ColumnModification[]
  }
}

export interface ColumnModification {
  name: string
  old_type: string | null
  new_type: string | null
  old_nullable: boolean | null
  new_nullable: boolean | null
  old_default: string | null
  new_default: string | null
}

export interface IndexInfo {
  name: string
  table_name: string
  definition: string
}

export const diffApi = {
  schema: (dbId: string, sourceType: string, sourceId: string, targetType: string, targetId: string) =>
    client.get<SchemaDiffResponse>(`/databases/${dbId}/diff/schema`, {
      params: { source_type: sourceType, source_id: sourceId, target_type: targetType, target_id: targetId }
    }),
}
