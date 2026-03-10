<template>
  <div class="page-docs">
    <div class="page-header">
      <h1 class="page-title">使用指南</h1>
    </div>

    <div class="docs-layout">
      <!-- Navigation Sidebar -->
      <nav class="docs-nav">
        <div class="nav-title">目录</div>
        <a v-for="item in tocItems" :key="item.id"
           :href="'#' + item.id"
           class="toc-item"
           :class="{ active: activeSection === item.id }"
           @click.prevent="scrollToSection(item.id)">
          {{ item.label }}
        </a>
      </nav>

      <!-- Content -->
      <div class="docs-content" ref="contentRef">
        <!-- Quick Start -->
        <div id="quickstart" class="doc-card">
          <div class="card-header"><h3>快速开始</h3></div>
          <div class="card-body">
            <ol class="doc-steps">
              <li>在<router-link to="/dashboard">总览</router-link>页面创建一个数据库</li>
              <li>等待状态变为"运行中"后，复制连接地址</li>
              <li>使用下方的客户端工具连接数据库</li>
            </ol>
            <div class="doc-note">
              数据库创建后会自动分配连接地址。挂起状态的数据库在收到连接请求时会自动唤醒（通常 &lt;10s）。
            </div>
          </div>
        </div>

        <!-- Connection Examples -->
        <div id="connection" class="doc-card">
          <div class="card-header"><h3>连接示例</h3></div>
          <div class="card-body">
            <p class="doc-text">以下示例中的连接地址请替换为数据库详情页中的实际连接地址。</p>

            <div class="code-section">
              <div class="code-title">
                <span>psql（命令行）</span>
                <button class="copy-btn" @click="copy(psqlCode)">{{ copyStates.psql || '复制' }}</button>
              </div>
              <pre class="code-block"><code>{{ psqlCode }}</code></pre>
            </div>

            <div class="code-section">
              <div class="code-title">
                <span>Python（psycopg2）</span>
                <button class="copy-btn" @click="copy(pythonCode, 'python')">{{ copyStates.python || '复制' }}</button>
              </div>
              <pre class="code-block"><code>{{ pythonCode }}</code></pre>
            </div>

            <div class="code-section">
              <div class="code-title">
                <span>Node.js（pg）</span>
                <button class="copy-btn" @click="copy(nodejsCode, 'nodejs')">{{ copyStates.nodejs || '复制' }}</button>
              </div>
              <pre class="code-block"><code>{{ nodejsCode }}</code></pre>
            </div>

            <div class="code-section">
              <div class="code-title">
                <span>Java（JDBC）</span>
                <button class="copy-btn" @click="copy(javaCode, 'java')">{{ copyStates.java || '复制' }}</button>
              </div>
              <pre class="code-block"><code>{{ javaCode }}</code></pre>
            </div>

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
        <div id="params" class="doc-card">
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
                  <td>SSL 模式（支持 TLS 加密，推荐 prefer 或 require）</td>
                  <td><code>prefer</code></td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Database Management -->
        <div id="database-mgmt" class="doc-card">
          <div class="card-header"><h3>数据库管理</h3></div>
          <div class="card-body">
            <h4 class="doc-subtitle">创建数据库</h4>
            <ol class="doc-steps">
              <li>进入<router-link to="/dashboard">总览</router-link>页面，点击「创建数据库」按钮</li>
              <li>输入数据库名称（仅支持小写字母、数字和连字符）</li>
              <li>选择计算规格（CU 数），1 CU = 1 vCPU + 4 GB 内存</li>
              <li>设置挂起超时时间（无活跃连接后自动挂起的等待时长）</li>
              <li>点击「创建」，等待状态变为「运行中」即可使用</li>
            </ol>

            <h4 class="doc-subtitle">数据库状态</h4>
            <table class="data-table">
              <thead>
                <tr><th>状态</th><th>说明</th><th>可执行操作</th></tr>
              </thead>
              <tbody>
                <tr><td><strong>运行中</strong></td><td>计算节点正在运行，可正常连接和查询</td><td>挂起、删除</td></tr>
                <tr><td><strong>已挂起</strong></td><td>计算节点已暂停，连接时自动唤醒</td><td>启动、删除</td></tr>
                <tr><td><strong>启动中</strong></td><td>正在创建或唤醒计算节点</td><td>等待完成</td></tr>
                <tr><td><strong>错误</strong></td><td>启动或运行过程中发生异常</td><td>查看日志、删除</td></tr>
              </tbody>
            </table>

            <h4 class="doc-subtitle">挂起与启动</h4>
            <p class="doc-text">挂起数据库会暂停计算节点以节省资源，但数据始终安全保存在对象存储中。挂起状态的数据库在收到连接请求时会自动唤醒，也可在总览页面手动点击「启动」。详细的唤醒机制请参考 <a href="#serverless" @click.prevent="scrollToSection('serverless')">Serverless 架构</a> 章节。</p>

            <h4 class="doc-subtitle">删除数据库</h4>
            <div class="doc-note">
              删除操作不可撤销。删除后数据库的所有数据、分支、备份将被永久清除。建议在删除前确认数据已备份或不再需要。
            </div>
          </div>
        </div>

        <!-- Serverless Architecture -->
        <div id="serverless" class="doc-card">
          <div class="card-header"><h3>Serverless 架构</h3></div>
          <div class="card-body">
            <h4 class="doc-subtitle">自动休眠与唤醒</h4>
            <p class="doc-text">DBay 采用存储计算分离的 Serverless 架构。数据持久化在对象存储中，计算节点（Compute Pod）按需创建和销毁。当数据库在设定时间内没有活跃连接时，计算节点会自动挂起以节省资源。</p>

            <h4 class="doc-subtitle" id="two-stage">两阶段超时机制</h4>
            <p class="doc-text">DBay 采用两阶段超时策略，在资源节约和响应速度之间取得平衡：</p>
            <table class="data-table">
              <thead>
                <tr><th>阶段</th><th>触发条件</th><th>默认超时</th><th>行为</th></tr>
              </thead>
              <tbody>
                <tr>
                  <td><strong>第一阶段：自动挂起</strong></td>
                  <td>无活跃连接</td>
                  <td>5 分钟</td>
                  <td>标记为「已挂起」，但 <strong>保留计算节点</strong></td>
                </tr>
                <tr>
                  <td><strong>第二阶段：节点回收</strong></td>
                  <td>挂起后持续无访问</td>
                  <td>30 分钟</td>
                  <td>删除计算节点，释放计算资源</td>
                </tr>
              </tbody>
            </table>
            <div class="doc-note">
              第一阶段超时（suspend_timeout）可在数据库设置中按实例单独调整。第二阶段为平台全局配置。有活跃连接时不会触发挂起。
            </div>

            <h4 class="doc-subtitle" id="warm-wake">热唤醒（Warm Wake）</h4>
            <p class="doc-text">数据库挂起后的 <strong>30 分钟内</strong>，计算节点仍然保留在内存中。在此期间收到连接请求时，系统直接复用已有节点，无需重新启动进程。</p>
            <table class="data-table">
              <thead>
                <tr><th>场景</th><th>实测延迟</th><th>说明</th></tr>
              </thead>
              <tbody>
                <tr><td>SQL 连接自动唤醒（通过 Proxy）</td><td><strong>~8ms</strong></td><td>Proxy 检测到挂起状态，直接恢复，用户几乎无感知</td></tr>
                <tr><td>手动点击「启动」</td><td><strong>~2s</strong></td><td>包含 K8s API 调用和操作日志记录的额外开销</td></tr>
              </tbody>
            </table>
            <div class="doc-note">适用场景：短暂空闲后的重新连接，例如开发者暂时离开又回来继续工作。绝大多数唤醒场景属于此类。</div>

            <h4 class="doc-subtitle" id="cold-wake">冷启动（Cold Start）</h4>
            <p class="doc-text">如果数据库挂起超过 30 分钟，计算节点已被回收，系统需要创建全新的计算节点并加载数据。</p>
            <table class="data-table">
              <thead>
                <tr><th>场景</th><th>实测延迟</th><th>说明</th></tr>
              </thead>
              <tbody>
                <tr><td>冷启动</td><td><strong>3 - 15 秒</strong></td><td>创建 Pod、拉取镜像（若已缓存则跳过）、启动 PostgreSQL、连接存储层</td></tr>
              </tbody>
            </table>
            <div class="doc-note">建议：客户端连接超时设置为 30 秒以上，以适应冷启动场景。</div>

            <h4 class="doc-subtitle">数据安全保障</h4>
            <p class="doc-text">无论热唤醒还是冷启动，数据始终安全。所有数据持久化在对象存储中，计算节点只是数据的"运行时视图"。挂起和唤醒不会造成任何数据丢失。</p>

            <h4 class="doc-subtitle">完整生命周期</h4>
            <table class="data-table">
              <thead>
                <tr><th>对比项</th><th>热唤醒 (Warm)</th><th>冷启动 (Cold)</th></tr>
              </thead>
              <tbody>
                <tr><td>触发条件</td><td>挂起后 30 分钟内重连</td><td>计算节点已被回收后重连</td></tr>
                <tr><td>唤醒时间</td><td>8ms ~ 2s</td><td>3 ~ 15 秒</td></tr>
                <tr><td>计算节点</td><td>复用已有节点</td><td>创建全新节点</td></tr>
                <tr><td>数据完整性</td><td>完整保留</td><td>完整保留</td></tr>
                <tr><td>用户体验</td><td>几乎无感知</td><td>首次查询需等待</td></tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- SQL Editor -->
        <div id="sql-editor" class="doc-card">
          <div class="card-header"><h3>SQL 编辑器</h3></div>
          <div class="card-body">
            <h4 class="doc-subtitle">功能说明</h4>
            <p class="doc-text">SQL 编辑器是内置的 Web 端数据库查询工具，无需安装客户端即可直接在浏览器中执行 SQL 语句。支持语法高亮、自动补全、多数据库切换和查询历史记录。</p>

            <h4 class="doc-subtitle">如何使用</h4>
            <ol class="doc-steps">
              <li>进入<router-link to="/sql">SQL 编辑器</router-link>页面</li>
              <li>在顶部下拉框中选择目标数据库（如果数据库处于挂起状态，会自动唤醒）</li>
              <li>在编辑区域输入 SQL 语句</li>
              <li>点击「执行」按钮或使用快捷键 Ctrl+Enter（Mac: Cmd+Enter）运行查询</li>
              <li>查询结果会显示在下方的表格中，支持列排序和数据复制</li>
            </ol>

            <h4 class="doc-subtitle">查询历史</h4>
            <p class="doc-text">每次执行的 SQL 语句会自动保存到本地历史记录中（最多 50 条）。点击工具栏的「历史」按钮可查看和重新加载之前的查询。历史记录保存在浏览器本地存储中，跨会话保留。</p>

            <div class="doc-note">
              SQL 编辑器通过 API 执行查询，与直接使用 psql 客户端连接效果相同。支持 SELECT、INSERT、UPDATE、DELETE、DDL 等所有 PostgreSQL 标准语句。
            </div>
          </div>
        </div>

        <!-- Branches -->
        <div id="branches" class="doc-card">
          <div class="card-header"><h3>分支管理</h3></div>
          <div class="card-body">
            <h4 class="doc-subtitle">什么是分支？</h4>
            <p class="doc-text">分支是数据库的 copy-on-write 快照。创建分支时，系统会基于当前数据创建一条新的时间线，两条时间线共享创建点之前的数据，之后各自独立演化。分支创建几乎零开销，不会复制实际数据。</p>

            <h4 class="doc-subtitle">适用场景</h4>
            <ul class="doc-list">
              <li><strong>开发测试</strong> — 从生产数据创建分支，在分支上测试新功能，不影响生产环境</li>
              <li><strong>数据回滚</strong> — 在执行危险操作前创建分支作为还原点，出错时切换回去</li>
              <li><strong>A/B 对比</strong> — 创建多个分支进行不同方案的数据验证</li>
              <li><strong>数据隔离</strong> — 为不同环境（开发/测试/预发布）提供各自的数据副本</li>
            </ul>

            <h4 class="doc-subtitle">如何使用</h4>
            <ol class="doc-steps">
              <li>进入数据库详情页 → 「分支」标签页</li>
              <li>点击「创建分支」，输入分支名称，系统会基于当前数据创建快照</li>
              <li>创建完成后，可以在分支列表中「切换」到目标分支</li>
              <li>切换分支会重启计算节点（约 10 秒），切换后所有连接将指向新分支的数据</li>
              <li>不再需要的分支可以删除，删除后其独有的数据会被回收</li>
            </ol>

            <div class="doc-note">
              注意：切换分支会断开当前所有数据库连接。建议在业务低峰期操作。主分支（main）不可删除。
            </div>
          </div>
        </div>

        <!-- Users -->
        <div id="users" class="doc-card">
          <div class="card-header"><h3>用户管理</h3></div>
          <div class="card-body">
            <h4 class="doc-subtitle">角色说明</h4>
            <table class="data-table">
              <thead>
                <tr>
                  <th>角色</th>
                  <th>权限范围</th>
                  <th>适用场景</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td><strong>Owner</strong></td>
                  <td>全部权限，可管理其他用户、修改数据库设置</td>
                  <td>数据库管理员</td>
                </tr>
                <tr>
                  <td><strong>Admin</strong></td>
                  <td>可执行 DDL（CREATE/ALTER/DROP TABLE 等），可读写数据</td>
                  <td>开发人员需要管理表结构</td>
                </tr>
                <tr>
                  <td><strong>Writer</strong></td>
                  <td>可执行 DML（INSERT/UPDATE/DELETE），可查询</td>
                  <td>应用程序连接，需要读写数据</td>
                </tr>
                <tr>
                  <td><strong>Reader</strong></td>
                  <td>仅 SELECT 查询权限</td>
                  <td>报表系统、只读分析</td>
                </tr>
              </tbody>
            </table>

            <h4 class="doc-subtitle">如何使用</h4>
            <ol class="doc-steps">
              <li>进入数据库详情页 → 「用户」标签页</li>
              <li>点击「创建用户」，输入用户名并选择角色</li>
              <li>创建成功后，系统会生成随机密码并显示一次，请立即保存</li>
              <li>如需重置密码，点击用户行的「重置密码」按钮</li>
              <li>不再需要的用户可以删除（Owner 用户不可删除）</li>
            </ol>

            <div class="doc-note">
              密码仅在创建和重置时显示一次，之后无法查看。请妥善保存密码。
            </div>
          </div>
        </div>

        <!-- Backups -->
        <div id="backups" class="doc-card">
          <div class="card-header"><h3>备份与恢复</h3></div>
          <div class="card-body">
            <h4 class="doc-subtitle">备份机制</h4>
            <p class="doc-text">备份会保存数据库某一时刻的完整快照。得益于 copy-on-write 存储架构，备份操作非常快速，不会影响数据库的正常使用。备份数据持久化在对象存储中，独立于计算节点。</p>

            <h4 class="doc-subtitle">适用场景</h4>
            <ul class="doc-list">
              <li><strong>定期备份</strong> — 在重要操作前手动创建备份，作为安全保障</li>
              <li><strong>版本快照</strong> — 在数据迁移、Schema 变更前保留当前状态</li>
              <li><strong>灾难恢复</strong> — 出现误操作时，从备份恢复到新实例</li>
            </ul>

            <h4 class="doc-subtitle">如何使用</h4>
            <ol class="doc-steps">
              <li>进入数据库详情页 → 「备份」标签页</li>
              <li>点击「创建备份」，系统会自动创建当前时间点的快照</li>
              <li>备份列表会显示所有历史备份及其创建时间</li>
              <li>需要恢复时，点击目标备份的「恢复」按钮</li>
              <li>恢复操作会创建一个新的数据库实例，不会覆盖当前数据库</li>
            </ol>

            <div class="doc-note">
              恢复操作会创建新实例，原数据库不受影响。建议定期清理不再需要的旧备份以节省存储空间。
            </div>
          </div>
        </div>

        <!-- Migration -->
        <div id="import" class="doc-card">
          <div class="card-header"><h3>数据迁移</h3></div>
          <div class="card-body">
            <h4 class="doc-subtitle">功能说明</h4>
            <p class="doc-text">数据迁移功能允许你从外部 PostgreSQL 数据库导入或持续同步数据到 DBay。支持三种模式：整库导入、按表导入和持续同步，迁移过程在后台运行，不影响目标数据库的正常使用。</p>

            <h4 class="doc-subtitle">迁移模式</h4>
            <table class="data-table">
              <thead>
                <tr><th>模式</th><th>说明</th><th>适用场景</th></tr>
              </thead>
              <tbody>
                <tr><td><strong>整库导入</strong></td><td>一次性导入源库所有表的数据</td><td>完整迁移到 DBay</td></tr>
                <tr><td><strong>按表导入</strong></td><td>选择指定的表进行一次性导入</td><td>部分数据迁移、测试环境搭建</td></tr>
                <tr><td><strong>持续同步</strong></td><td>基于 PostgreSQL 逻辑复制，实时同步源库的数据变更</td><td>双写过渡期、实时数据分析</td></tr>
              </tbody>
            </table>

            <h4 class="doc-subtitle">一次性导入</h4>
            <ol class="doc-steps">
              <li>在侧边栏进入「数据迁移」页面，选择目标数据库</li>
              <li>点击「导入数据」按钮，进入导入向导</li>
              <li>填写源数据库的连接信息，点击「测试连接」验证</li>
              <li>选择导入模式（整库导入或按表选择）</li>
              <li>选择冲突策略（追加数据或覆盖），确认后提交</li>
              <li>在任务列表中查看导入进度，支持暂停、恢复和取消</li>
            </ol>

            <h4 class="doc-subtitle">持续同步</h4>
            <ol class="doc-steps">
              <li>在导入向导中选择「持续同步」模式</li>
              <li>系统会检测源库的 <code>wal_level</code> 是否为 <code>logical</code>，以及用户是否有复制权限</li>
              <li>选择要同步的表，确认后提交同步任务</li>
              <li>系统先执行初始数据复制，完成后自动进入实时同步状态</li>
              <li>在任务详情页可查看同步状态、复制延迟和各表的同步进度</li>
              <li>支持暂停/恢复同步，不再需要时可停止并选择是否清理源库的复制资源</li>
            </ol>

            <div class="doc-note">
              源数据库必须允许来自 DBay 服务器的网络连接。一次性导入仅进行只读操作；持续同步需要源库开启逻辑复制（<code>wal_level = logical</code>）且连接用户具有 <code>REPLICATION</code> 权限。
            </div>
          </div>
        </div>

        <!-- Audit -->
        <div id="audit" class="doc-card">
          <div class="card-header"><h3>审计日志</h3></div>
          <div class="card-body">
            <h4 class="doc-subtitle">功能说明</h4>
            <p class="doc-text">审计日志基于 PostgreSQL 的 <code>pgaudit</code> 扩展，可记录数据库的各类操作。支持按操作类型分别开启，帮助你追踪数据变更、排查问题、满足合规要求。</p>

            <h4 class="doc-subtitle">审计类型</h4>
            <table class="data-table">
              <thead>
                <tr>
                  <th>类型</th>
                  <th>记录内容</th>
                  <th>建议</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td><strong>DDL</strong></td>
                  <td>CREATE、ALTER、DROP 等表结构变更</td>
                  <td>建议始终开启，日志量小</td>
                </tr>
                <tr>
                  <td><strong>DML</strong></td>
                  <td>INSERT、UPDATE、DELETE 数据操作</td>
                  <td>按需开启，高频写入场景日志量较大</td>
                </tr>
                <tr>
                  <td><strong>SELECT</strong></td>
                  <td>所有查询操作</td>
                  <td>仅在需要时开启，日志量最大</td>
                </tr>
              </tbody>
            </table>

            <h4 class="doc-subtitle">如何使用</h4>
            <ol class="doc-steps">
              <li>进入数据库详情页 → 「审计」标签页</li>
              <li>勾选需要审计的操作类型（DDL / DML / SELECT）</li>
              <li>开启后，对应类型的操作会被记录到审计日志中</li>
              <li>在审计日志列表中查看和筛选历史记录</li>
            </ol>

            <div class="doc-note">
              审计日志会占用存储空间。建议根据实际需要选择性开启，避免不必要地开启 SELECT 审计导致日志量过大。
            </div>
          </div>
        </div>

        <!-- Monitoring -->
        <div id="monitor" class="doc-card">
          <div class="card-header"><h3>监控面板</h3></div>
          <div class="card-body">
            <h4 class="doc-subtitle">功能说明</h4>
            <p class="doc-text"><router-link to="/monitor">监控面板</router-link>提供数据库运行状态的全方位监控，分为四个维度：</p>

            <h4 class="doc-subtitle">服务总览</h4>
            <p class="doc-text">展示全局指标概览，包括数据库总数、运行中/已挂起数量、总存储用量等，以及每个数据库的状态列表。</p>

            <h4 class="doc-subtitle">唤醒监控</h4>
            <p class="doc-text">Serverless 数据库的核心体验指标。监控唤醒次数、平均唤醒延迟、唤醒成功率和冷启动比例。延迟分布图帮助识别异常唤醒，唤醒记录列表可追溯每次唤醒的详细信息。</p>

            <h4 class="doc-subtitle">性能诊断</h4>
            <p class="doc-text">选择具体数据库后查看实时性能指标（CPU、内存、慢查询）。系统会自动诊断性能瓶颈类型：</p>
            <ul class="doc-list">
              <li><strong>SQL 性能问题</strong> — 存在慢查询但 CPU/内存使用率正常，建议优化 SQL 语句</li>
              <li><strong>资源不足</strong> — CPU 或内存使用率过高，建议升级计算规格</li>
              <li><strong>混合问题</strong> — 两者兼有，需综合排查</li>
            </ul>

            <h4 class="doc-subtitle">用量统计</h4>
            <p class="doc-text">展示存储排行、分支存储详情和唤醒频次分析。帮助识别存储占用大的数据库和空闲分支，优化资源使用。</p>
          </div>
        </div>

        <!-- Logs -->
        <div id="logs" class="doc-card">
          <div class="card-header"><h3>日志管理</h3></div>
          <div class="card-body">
            <h4 class="doc-subtitle">功能说明</h4>
            <p class="doc-text"><router-link to="/logs">日志管理</router-link>集中查看所有数据库的运维日志，分为三个类别：</p>

            <h4 class="doc-subtitle">操作日志</h4>
            <p class="doc-text">记录所有数据库的生命周期操作，包括创建、启动、挂起、恢复、删除等。可按数据库筛选，支持搜索操作类型和错误信息。</p>

            <h4 class="doc-subtitle">SQL 审计日志</h4>
            <p class="doc-text">选择具体数据库后查看其 SQL 审计记录。需要先在数据库详情页的「审计」标签页中开启审计功能。支持按操作类型（DDL/DML/SELECT）筛选。</p>

            <h4 class="doc-subtitle">错误日志</h4>
            <p class="doc-text">选择具体数据库后查看其 PostgreSQL 运行日志。可按日志级别（ERROR/WARNING/LOG 等）筛选，帮助排查数据库运行问题。</p>

            <div class="doc-note">
              操作日志为全局视图，无需选择数据库。审计日志和错误日志需要先选择目标数据库。
            </div>
          </div>
        </div>

        <!-- API Key -->
        <div id="apikey" class="doc-card">
          <div class="card-header"><h3>API Key</h3></div>
          <div class="card-body">
            <h4 class="doc-subtitle">功能说明</h4>
            <p class="doc-text">API Key 是访问 DBay 控制台和 API 的身份凭证。每个租户拥有唯一的 API Key，用于所有管理操作的身份验证。</p>

            <h4 class="doc-subtitle">如何使用</h4>
            <ol class="doc-steps">
              <li>进入<router-link to="/apikey">API Key</router-link>页面查看当前 Key</li>
              <li>如需重新生成，点击「重新生成」按钮</li>
              <li>新 Key 生成后会显示一次，请立即复制保存</li>
              <li>在 API 请求中通过 <code>X-API-Key</code> 请求头传递 Key</li>
            </ol>

            <div class="doc-note">
              重新生成 API Key 后，旧 Key 立即失效。所有使用旧 Key 的客户端和应用需要更新为新 Key。请妥善保管，不要泄露给他人。
            </div>
          </div>
        </div>

        <!-- Billing -->
        <div id="billing" class="doc-card">
          <div class="card-header"><h3>计费说明</h3></div>
          <div class="card-body">
            <h4 class="doc-subtitle">计费模式</h4>
            <p class="doc-text">DBay 采用按量计费模式，费用由计算费用和存储费用两部分组成。当前为免费公测阶段，所有用量均不产生实际费用。</p>

            <h4 class="doc-subtitle">计算费用</h4>
            <p class="doc-text">计算费用按数据库实际运行时间计费，挂起状态不计费。计量单位为 CU·小时（1 CU = 1 vCPU + 4 GB 内存）。</p>

            <h4 class="doc-subtitle">存储费用</h4>
            <p class="doc-text">存储费用按实际数据占用空间计费，包含数据和 WAL 日志。按 GB·月计量，即使数据库处于挂起状态，存储也会持续计费。</p>

            <div class="doc-note">
              可在<router-link to="/usage">用量与计费</router-link>页面查看详细的用量和费用明细。
            </div>
          </div>
        </div>

        <!-- FAQ -->
        <div id="faq" class="doc-card">
          <div class="card-header"><h3>常见问题</h3></div>
          <div class="card-body">
            <div class="faq-item">
              <div class="faq-q">Q: 连接超时怎么办？</div>
              <div class="faq-a">如果数据库处于挂起状态，首次连接需要等待唤醒（通常 &lt;10s）。请设置客户端连接超时为 30 秒以上。详见 <a href="#serverless" @click.prevent="scrollToSection('serverless')">Serverless 架构</a>。</div>
            </div>
            <div class="faq-item">
              <div class="faq-q">Q: 数据库挂起后数据会丢失吗？</div>
              <div class="faq-a">不会。数据持久化在对象存储中，挂起只是停止计算节点。恢复后数据完整可用。</div>
            </div>
            <div class="faq-item">
              <div class="faq-q">Q: 如何修改数据库密码？</div>
              <div class="faq-a">连接数据库后执行 <code>ALTER USER cloud_admin PASSWORD 'new_password';</code>，或在数据库详情页的「用户」标签页中重置密码。</div>
            </div>
            <div class="faq-item">
              <div class="faq-q">Q: API Key 丢失了怎么办？</div>
              <div class="faq-a">在 <router-link to="/apikey">API Key</router-link> 页面重新生成。注意：重新生成后旧 Key 立即失效。</div>
            </div>
            <div class="faq-item">
              <div class="faq-q">Q: 查询很慢，是 SQL 问题还是资源不足？</div>
              <div class="faq-a">前往<router-link to="/monitor">监控面板</router-link>的「性能诊断」标签页，系统会自动分析并给出诊断建议。</div>
            </div>
            <div class="faq-item">
              <div class="faq-q">Q: 存储费用是怎么算的？</div>
              <div class="faq-a">存储按实际数据占用空间按 GB·月计费，即使数据库挂起也会持续计费。详见 <a href="#billing" @click.prevent="scrollToSection('billing')">计费说明</a>。</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { copyToClipboard } from '../../utils/clipboard'

const route = useRoute()
const activeSection = ref('quickstart')

const tocItems = [
  { id: 'quickstart', label: '快速开始' },
  { id: 'connection', label: '连接示例' },
  { id: 'params', label: '连接参数' },
  { id: 'database-mgmt', label: '数据库管理' },
  { id: 'serverless', label: 'Serverless 架构' },
  { id: 'sql-editor', label: 'SQL 编辑器' },
  { id: 'branches', label: '分支管理' },
  { id: 'users', label: '用户管理' },
  { id: 'backups', label: '备份与恢复' },
  { id: 'import', label: '数据迁移' },
  { id: 'audit', label: '审计日志' },
  { id: 'monitor', label: '监控面板' },
  { id: 'logs', label: '日志管理' },
  { id: 'apikey', label: 'API Key' },
  { id: 'billing', label: '计费说明' },
  { id: 'faq', label: '常见问题' },
]

function scrollToSection(id: string) {
  const el = document.getElementById(id)
  if (!el) return
  // Scroll within .console-main (the parent scrollable container)
  const scrollContainer = el.closest('.console-main') || el.closest('.docs-content')
  if (scrollContainer) {
    const offset = el.getBoundingClientRect().top - scrollContainer.getBoundingClientRect().top + scrollContainer.scrollTop - 16
    scrollContainer.scrollTo({ top: offset, behavior: 'smooth' })
  } else {
    el.scrollIntoView({ behavior: 'smooth' })
  }
  activeSection.value = id
}

function handleScroll() {
  const container = document.querySelector('.console-main')
  if (!container) return
  const containerTop = container.getBoundingClientRect().top + 32

  for (let i = tocItems.length - 1; i >= 0; i--) {
    const item = tocItems[i]
    if (!item) continue
    const el = document.getElementById(item.id)
    if (el && el.getBoundingClientRect().top <= containerTop) {
      activeSection.value = item.id
      return
    }
  }
  if (tocItems[0]) activeSection.value = tocItems[0].id
}

let scrollContainer: Element | null = null

onMounted(async () => {
  await nextTick()
  scrollContainer = document.querySelector('.console-main')
  if (scrollContainer) {
    scrollContainer.addEventListener('scroll', handleScroll, { passive: true })
  }

  // Handle initial hash from route (e.g. /docs#serverless)
  if (route.hash) {
    const id = route.hash.slice(1)
    // Wait for the page to fully render
    setTimeout(() => scrollToSection(id), 100)
  }
})

onUnmounted(() => {
  if (scrollContainer) {
    scrollContainer.removeEventListener('scroll', handleScroll)
  }
})

const copyStates = reactive<Record<string, string>>({})

async function copy(text: string, key = 'psql') {
  await copyToClipboard(text)
  copyStates[key] = '已复制'
  setTimeout(() => { copyStates[key] = '' }, 2000)
}

const psqlCode = `psql "host=<HOST> port=4432 user=cloud_admin dbname=postgres sslmode=prefer"`

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
  ssl: { rejectUnauthorized: false },
  connectionTimeoutMillis: 30000,  // 预留唤醒时间
});

await client.connect();
const res = await client.query('SELECT version()');
console.log(res.rows[0]);
await client.end();`

const javaCode = `import java.sql.*;

String url = "jdbc:postgresql://<HOST>:4432/postgres?sslmode=prefer&loginTimeout=30";
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
    connStr := "host=<HOST> port=4432 user=cloud_admin dbname=postgres sslmode=prefer connect_timeout=30"
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
.docs-layout {
  display: flex;
  gap: 24px;
}

.docs-nav {
  width: 160px;
  flex-shrink: 0;
  position: sticky;
  top: 0;
  max-height: calc(100vh - 120px);
  overflow-y: auto;
}

.nav-title {
  font-size: 13px;
  font-weight: 600;
  color: #8a8e99;
  margin-bottom: 8px;
  padding-left: 12px;
}

.toc-item {
  display: block;
  padding: 6px 12px;
  font-size: 13px;
  color: #575d6c;
  text-decoration: none;
  border-left: 2px solid transparent;
  transition: all 0.15s;
  line-height: 1.5;
}

.toc-item:hover {
  color: #0073e6;
  background: #f5f7fa;
}

.toc-item.active {
  color: #0073e6;
  font-weight: 500;
  border-left-color: #0073e6;
  background: #f0f7ff;
}

.docs-content {
  flex: 1;
  min-width: 0;
}

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

.doc-note a {
  color: #0073e6;
  text-decoration: none;
}

.doc-note a:hover {
  text-decoration: underline;
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

.doc-subtitle {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
  margin: 20px 0 8px;
}

.doc-subtitle:first-child {
  margin-top: 0;
}

.doc-list {
  padding-left: 20px;
  margin: 0 0 16px;
  line-height: 2;
  font-size: 14px;
  color: #333;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
  margin-bottom: 16px;
}

.data-table th {
  background: #f5f7fa;
  padding: 8px 12px;
  text-align: left;
  font-weight: 600;
  color: #191919;
  border-bottom: 1px solid #dfe1e6;
}

.data-table td {
  padding: 8px 12px;
  border-bottom: 1px solid #ebebeb;
  color: #333;
}

.data-table code {
  background: #f2f3f5;
  padding: 1px 6px;
  border-radius: 2px;
  font-size: 13px;
  color: #191919;
}

@media (max-width: 768px) {
  .docs-nav {
    display: none;
  }
}
</style>
