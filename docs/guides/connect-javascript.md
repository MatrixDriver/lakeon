# JavaScript / TypeScript 连接指南

## Prisma + Next.js

### 1. 安装依赖

```bash
npm install prisma @prisma/client
npx prisma init
```

### 2. 配置连接串

`.env`:
```env
DATABASE_URL="postgres://user_xxx:PASSWORD@pg.dbay.cloud:4432/my-app-db?options=endpoint%3Dmy-app-db&sslmode=require"
```

### 3. 定义 Schema

`prisma/schema.prisma`:
```prisma
generator client {
  provider = "prisma-client-js"
}

datasource db {
  provider = "postgresql"
  url      = env("DATABASE_URL")
}

model Post {
  id        Int      @id @default(autoincrement())
  title     String
  content   String?
  published Boolean  @default(false)
  createdAt DateTime @default(now()) @map("created_at")

  @@map("posts")
}
```

### 4. 推送 Schema 到数据库

```bash
npx prisma db push
```

### 5. 使用

```typescript
import { PrismaClient } from '@prisma/client'

const prisma = new PrismaClient()

// 创建
const post = await prisma.post.create({
  data: { title: 'Hello DBay', content: 'My first serverless post!' }
})

// 查询
const posts = await prisma.post.findMany()
console.log(posts)
```

## Node.js pg 驱动（原生）

```bash
npm install pg
```

```javascript
const { Client } = require('pg')

const client = new Client({
  connectionString: process.env.DATABASE_URL,
  ssl: { rejectUnauthorized: false }
})

await client.connect()
const res = await client.query('SELECT NOW()')
console.log(res.rows[0])
await client.end()
```

## Drizzle ORM

```bash
npm install drizzle-orm postgres
npm install -D drizzle-kit
```

```typescript
import { drizzle } from 'drizzle-orm/postgres-js'
import postgres from 'postgres'

const sql = postgres(process.env.DATABASE_URL!)
const db = drizzle(sql)

const result = await db.execute('SELECT 1 as connected')
console.log(result) // [{ connected: 1 }]
```

## 注意事项

- **连接串中的 `options=endpoint%3D<db-name>`** 是必须的，Proxy 用它路由到正确的数据库
- 首次连接休眠数据库会有 ~3 秒延迟（自动唤醒），后续连接秒级响应
- 建议使用连接池（Prisma 默认启用）避免频繁建连
