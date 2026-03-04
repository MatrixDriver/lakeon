<template>
  <div class="page-docs">
    <div class="page-header">
      <h1 class="page-title">使用指南</h1>
    </div>

    <!-- Quick Start -->
    <div class="doc-card">
      <div class="card-header"><h3>快速开始</h3></div>
      <div class="card-body">
        <ol class="doc-steps">
          <li>在<router-link to="/databases">数据库实例</router-link>页面创建一个数据库</li>
          <li>等待状态变为"运行中"后，复制连接地址</li>
          <li>使用下方的客户端工具连接数据库</li>
        </ol>
        <div class="doc-note">
          数据库创建后会自动分配连接地址。挂起状态的数据库在收到连接请求时会自动唤醒（通常 &lt;10s）。
        </div>
      </div>
    </div>

    <!-- Connection Examples -->
    <div class="doc-card">
      <div class="card-header"><h3>连接示例</h3></div>
      <div class="card-body">
        <p class="doc-text">以下示例中的连接地址请替换为数据库详情页中的实际连接地址。</p>

        <!-- psql -->
        <div class="code-section">
          <div class="code-title">
            <span>psql（命令行）</span>
            <button class="copy-btn" @click="copy(psqlCode)">{{ copyStates.psql || '复制' }}</button>
          </div>
          <pre class="code-block"><code>{{ psqlCode }}</code></pre>
        </div>

        <!-- Python -->
        <div class="code-section">
          <div class="code-title">
            <span>Python（psycopg2）</span>
            <button class="copy-btn" @click="copy(pythonCode, 'python')">{{ copyStates.python || '复制' }}</button>
          </div>
          <pre class="code-block"><code>{{ pythonCode }}</code></pre>
        </div>

        <!-- Node.js -->
        <div class="code-section">
          <div class="code-title">
            <span>Node.js（pg）</span>
            <button class="copy-btn" @click="copy(nodejsCode, 'nodejs')">{{ copyStates.nodejs || '复制' }}</button>
          </div>
          <pre class="code-block"><code>{{ nodejsCode }}</code></pre>
        </div>

        <!-- Java JDBC -->
        <div class="code-section">
          <div class="code-title">
            <span>Java（JDBC）</span>
            <button class="copy-btn" @click="copy(javaCode, 'java')">{{ copyStates.java || '复制' }}</button>
          </div>
          <pre class="code-block"><code>{{ javaCode }}</code></pre>
        </div>

        <!-- Go -->
        <div class="code-section">
          <div class="code-title">
            <span>Go（pgx）</span>
            <button class="copy-btn" @click="copy(goCode, 'go')">{{ copyStates.go || '复制' }}</button>
          </div>
          <pre class="code-block"><code>{{ goCode }}</code></pre>
        </div>
      </div>
    </div>

    <!-- Connection Parameters -->
    <div class="doc-card">
      <div class="card-header"><h3>连接参数</h3></div>
      <div class="card-body">
        <table class="data-table">
          <thead>
            <tr>
              <th>参数</th>
              <th>说明</th>
              <th>示例</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td><code>host</code></td>
              <td>数据库连接地址（Proxy 入口）</td>
              <td><code>&lt;EIP&gt;</code></td>
            </tr>
            <tr>
              <td><code>port</code></td>
              <td>PostgreSQL 连接端口</td>
              <td><code>4432</code></td>
            </tr>
            <tr>
              <td><code>user</code></td>
              <td>数据库用户名</td>
              <td><code>cloud_admin</code></td>
            </tr>
            <tr>
              <td><code>database</code></td>
              <td>数据库名</td>
              <td><code>postgres</code></td>
            </tr>
            <tr>
              <td><code>sslmode</code></td>
              <td>SSL 模式（当前环境可设为 disable）</td>
              <td><code>disable</code></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- FAQ -->
    <div class="doc-card">
      <div class="card-header"><h3>常见问题</h3></div>
      <div class="card-body">
        <div class="faq-item">
          <div class="faq-q">Q: 连接超时怎么办？</div>
          <div class="faq-a">如果数据库处于挂起状态，首次连接需要等待唤醒（通常 &lt;10s）。请设置客户端连接超时为 30 秒以上。</div>
        </div>
        <div class="faq-item">
          <div class="faq-q">Q: 数据库挂起后数据会丢失吗？</div>
          <div class="faq-a">不会。数据持久化在对象存储中，挂起只是停止计算节点。恢复后数据完整可用。</div>
        </div>
        <div class="faq-item">
          <div class="faq-q">Q: 如何修改数据库密码？</div>
          <div class="faq-a">连接数据库后执行 <code>ALTER USER cloud_admin PASSWORD 'new_password';</code></div>
        </div>
        <div class="faq-item">
          <div class="faq-q">Q: API Key 丢失了怎么办？</div>
          <div class="faq-a">在 <router-link to="/apikey">API Key</router-link> 页面重新生成。注意：重新生成后旧 Key 立即失效。</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue'
import { copyToClipboard } from '../../utils/clipboard'

const copyStates = reactive<Record<string, string>>({})

async function copy(text: string, key = 'psql') {
  await copyToClipboard(text)
  copyStates[key] = '已复制'
  setTimeout(() => { copyStates[key] = '' }, 2000)
}

const psqlCode = `psql "host=<HOST> port=4432 user=cloud_admin dbname=postgres sslmode=disable"`

const pythonCode = `import psycopg2

conn = psycopg2.connect(
    host="<HOST>",
    port=4432,
    user="cloud_admin",
    dbname="postgres",
    sslmode="disable",
    connect_timeout=30,  # 预留唤醒时间
)
cursor = conn.cursor()
cursor.execute("SELECT version()")
print(cursor.fetchone())
conn.close()`

const nodejsCode = `const { Client } = require('pg');

const client = new Client({
  host: '<HOST>',
  port: 4432,
  user: 'cloud_admin',
  database: 'postgres',
  ssl: false,
  connectionTimeoutMillis: 30000,  // 预留唤醒时间
});

await client.connect();
const res = await client.query('SELECT version()');
console.log(res.rows[0]);
await client.end();`

const javaCode = `import java.sql.*;

String url = "jdbc:postgresql://<HOST>:4432/postgres?sslmode=disable&loginTimeout=30";
Connection conn = DriverManager.getConnection(url, "cloud_admin", "");

Statement stmt = conn.createStatement();
ResultSet rs = stmt.executeQuery("SELECT version()");
if (rs.next()) {
    System.out.println(rs.getString(1));
}
conn.close();`

const goCode = `package main

import (
    "context"
    "fmt"
    "github.com/jackc/pgx/v5"
)

func main() {
    connStr := "host=<HOST> port=4432 user=cloud_admin dbname=postgres sslmode=disable connect_timeout=30"
    conn, err := pgx.Connect(context.Background(), connStr)
    if err != nil {
        panic(err)
    }
    defer conn.Close(context.Background())

    var version string
    conn.QueryRow(context.Background(), "SELECT version()").Scan(&version)
    fmt.Println(version)
}`
</script>

<style scoped>
.doc-card {
  background: #fff;
  border: 1px solid #dfe1e6;
  border-radius: 2px;
  overflow: hidden;
  margin-bottom: 20px;
}

.card-header {
  padding: 14px 20px;
  border-bottom: 1px solid #ebebeb;
}

.card-header h3 {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
  margin: 0;
}

.card-body {
  padding: 20px;
}

.doc-steps {
  padding-left: 20px;
  margin: 0 0 16px;
  line-height: 2;
  font-size: 14px;
  color: #333;
}

.doc-steps a {
  color: #0073e6;
  text-decoration: none;
}

.doc-steps a:hover {
  text-decoration: underline;
}

.doc-note {
  background: #f2f6fc;
  border-left: 3px solid #0073e6;
  padding: 10px 16px;
  font-size: 13px;
  color: #575d6c;
  border-radius: 0 2px 2px 0;
}

.doc-text {
  font-size: 14px;
  color: #575d6c;
  margin-bottom: 20px;
}

.code-section {
  margin-bottom: 20px;
}

.code-section:last-child {
  margin-bottom: 0;
}

.code-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.code-title span {
  font-size: 13px;
  font-weight: 600;
  color: #191919;
}

.code-block {
  background: #282c34;
  color: #abb2bf;
  border-radius: 2px;
  padding: 16px;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.6;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  margin: 0;
}

.copy-btn {
  background: none;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
  padding: 2px 10px;
  font-size: 12px;
  color: #0073e6;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s;
}

.copy-btn:hover {
  border-color: #0073e6;
  background-color: #f2f6fc;
}

.faq-item {
  margin-bottom: 16px;
}

.faq-item:last-child {
  margin-bottom: 0;
}

.faq-q {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
  margin-bottom: 6px;
}

.faq-a {
  font-size: 14px;
  color: #575d6c;
  line-height: 1.6;
}

.faq-a code {
  background: #f2f3f5;
  padding: 1px 6px;
  border-radius: 2px;
  font-size: 13px;
  color: #191919;
}

.faq-a a {
  color: #0073e6;
  text-decoration: none;
}

.faq-a a:hover {
  text-decoration: underline;
}
</style>
