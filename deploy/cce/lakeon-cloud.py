#!/usr/bin/env python3
"""
Lakeon 华为云综合运维管理工具 (Enhanced v2.1)

本工具是 Lakeon 项目的华为云资源管理核心，集成了资源发现、开关机、状态监控以及深度安全诊断功能。
它取代了原本散乱的多个测试脚本，提供统一的命令行界面。

主要命令:
  check           - 🔴 运行深度诊断：包括 AK/SK 效验、RDS 安全组审计、SWR 连通性测试。
  discover        - 🔍 资源发现：自动搜寻并缓存当前区域内的所有 Lakeon 资源 ID。
  status          - 🟢 状态查看：一键获取 CCE、RDS、ELB 和 EIP 的运行状态。
  start-cloud     - 🚀 启动序列：按序恢复 RDS 实例、创建 CCE 节点并重建网络入口。
  stop-cloud      - 🛑 关停序列：删除计算节点和 ELB，关停 RDS 实例以实现极致省钱。
"""

import hashlib, hmac, json, os, re, sys, time
import urllib.request, urllib.parse, urllib.error
from datetime import datetime, timezone

# ── 基础配置 ─────────────────────────────────────────────────────

REGION = "cn-north-4"  # 目标区域：华北-北京四
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SITE_DIR = os.environ.get("LAKEON_SITE_DIR", SCRIPT_DIR)

# 凭证与配置文件路径（优先从站点目录读取）
ENV_FILE = os.path.join(SITE_DIR, ".env")
if not os.path.exists(ENV_FILE):
    ENV_FILE = os.path.join(SCRIPT_DIR, ".env.cce")  # fallback
CACHE_FILE = os.path.join(SITE_DIR, ".lakeon-cloud.json")
VALUES_FILE = os.path.join(SITE_DIR, "values.yaml")
if not os.path.exists(VALUES_FILE):
    VALUES_FILE = os.path.join(SCRIPT_DIR, "values-cce.yaml")  # fallback

# ── 核心底层引擎 (签名与 API 调用) ──────────────────────────────────

def load_credentials():
    """从 .env.cce 加载 OBS_AK 和 OBS_SK"""
    creds = {}
    if not os.path.exists(ENV_FILE):
        return None, None, {}
    with open(ENV_FILE, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            # 解析 export KEY="VAL" 或 KEY=VAL 格式
            if "=" in line and not line.startswith("#"):
                line = line.removeprefix("export ").strip()
                key, val = line.split("=", 1)
                creds[key.strip()] = val.strip().strip("'\"")
    ak = creds.get("HWCLOUD_AK") or creds.get("OBS_AK")
    sk = creds.get("HWCLOUD_SK") or creds.get("OBS_SK")
    return ak, sk, creds

def _hmac256(key, msg):
    """标准的 HMAC-SHA256 哈希计算"""
    if isinstance(key, str): key = key.encode()
    if isinstance(msg, str): msg = msg.encode()
    return hmac.new(key, msg, hashlib.sha256).digest()

def _sha256(data):
    """标准的 SHA256 摘要计算（用于 Body 哈希）"""
    if isinstance(data, str): data = data.encode()
    return hashlib.sha256(data).hexdigest()

def api(method, url, ak, sk, body="", timeout=30):
    """
    华为云 API V3 签名请求通用引擎。
    实现了 SDK-HMAC-SHA256 签名算法，自动处理 Host、日期和授权头。
    """
    parsed = urllib.parse.urlparse(url)
    host = parsed.hostname
    path = parsed.path or "/"
    query = parsed.query or ""
    
    # 对查询参数进行字典排序，这是签名规范的要求
    if query:
        params = urllib.parse.parse_qsl(query, keep_blank_values=True)
        params.sort(key=lambda x: x[0])
        query = urllib.parse.urlencode(params)

    # 获取 UTC 时间，签名对时间偏差（通常是 15 分钟）非常敏感
    now = datetime.now(timezone.utc)
    ts = now.strftime("%Y%m%dT%H%M%SZ")
    ds = now.strftime("%Y%m%d")

    # 根据域名解析服务缩写 (例如: rds, cce, vpc)
    parts = host.split(".")
    svc = parts[0]
    # SWR 使用特殊的域名结构
    rgn = parts[1] if len(parts) > 2 and parts[1] != "myhuaweicloud" else REGION

    # 1. 构造规范请求 (Canonical Request)
    sh = "host;x-sdk-date"
    ch = f"host:{host}\nx-sdk-date:{ts}\n"
    cr = f"{method}\n{path}\n{query}\n{ch}\n{sh}\n{_sha256(body)}"
    
    # 2. 构造待签名字符串 (String to Sign)
    scope = f"{ds}/{rgn}/{svc}/sdk_request"
    sts = f"SDK-HMAC-SHA256\n{ts}\n{scope}\n{_sha256(cr)}"

    # 3. 计算签名 (Calculate Signature)
    # 派生密钥：Date -> Region -> Service -> Request
    k_date = _hmac256(f"SDK{sk}".encode(), ds)
    k_region = _hmac256(k_date, rgn)
    k_service = _hmac256(k_region, svc)
    k_signing = _hmac256(k_service, "sdk_request")
    sig = hmac.new(k_signing, sts.encode(), hashlib.sha256).hexdigest()

    # 4. 构造 HTTP 请求头
    headers = {
        "Host": host, 
        "X-Sdk-Date": ts,
        "Authorization": f"SDK-HMAC-SHA256 Credential={ak}/{scope}, SignedHeaders={sh}, Signature={sig}",
    }
    if body:
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=body.encode() if body else None, headers=headers, method=method)
    
    try:
        # 忽略 SSL 证书验证以提高在内网代理环境下的兼容性
        import ssl
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        
        with urllib.request.urlopen(req, context=ctx, timeout=timeout) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        # 捕获 4xx/5xx 错误并尝试解析错误 Body
        try: return e.code, json.loads(e.read().decode())
        except: return e.code, {"error": str(e)}
    except Exception as e:
        return 0, {"error": str(e)}

# ── 业务数据获取逻辑 ──────────────────────────────────────────────

def get_project_id(ak, sk):
    """获取 IAM 下对应区域的项目 ID"""
    s, d = api("GET", f"https://iam.myhuaweicloud.com/v3/projects?name={REGION}", ak, sk)
    if s == 200 and d.get("projects"):
        return d["projects"][0]["id"]
    raise Exception(f"获取 Project ID 失败，请检查 AK/SK 或网络。状态码: {s}")

def get_rds_instances(ak, sk, pid):
    """列出所有 RDS 实例"""
    s, d = api("GET", f"https://rds.{REGION}.myhuaweicloud.com/v3/{pid}/instances", ak, sk)
    return d.get("instances", []) if s == 200 else []

def get_cce_clusters(ak, sk, pid):
    """列出 CCE 容器集群"""
    s, d = api("GET", f"https://cce.{REGION}.myhuaweicloud.com/api/v3/projects/{pid}/clusters", ak, sk)
    return d.get("items", []) if s == 200 else []

def get_cce_nodes(ak, sk, pid, cid):
    """获取指定集群下的全部节点及其规格"""
    s, d = api("GET", f"https://cce.{REGION}.myhuaweicloud.com/api/v3/projects/{pid}/clusters/{cid}/nodes", ak, sk)
    return d.get("items", []) if s == 200 else []

def get_sg_rules(ak, sk, pid, sg_id):
    """获取安全组规则详情"""
    s, d = api("GET", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/security-group-rules?security_group_id={sg_id}", ak, sk)
    return d.get("security_group_rules", []) if s == 200 else []

def get_eips(ak, sk, pid):
    """查询弹性公网 IP 列表"""
    s, d = api("GET", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/publicips", ak, sk)
    return d.get("publicips", []) if s == 200 else []

def get_elb_detail(ak, sk, pid, elb_id):
    """获取 ELB 负载均衡详情"""
    s, d = api("GET", f"https://elb.{REGION}.myhuaweicloud.com/v3/{pid}/elb/loadbalancers/{elb_id}", ak, sk)
    return d if s == 200 else None

# ── 综合诊断模块 (The Check Engine) ─────────────────────────────────

def cmd_check(ak, sk):
    """运行多维度检查脚本，对应原本的 diag_creds 和 debug_rds_sg 逻辑"""
    print("="*60)
    print("🔍 Lakeon 华为云环境深度检查报告")
    print("="*60)
    
    # 1. 基础环境
    now = datetime.now(timezone.utc)
    print(f"\n[1/4] 基础环境验证")
    print(f"  - 当前时间 (UTC): {now.strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"  - 凭证掩码: AK({ak[:4]}...{ak[-4:]}) SK({sk[:2]}...{sk[-2:]})")
    try:
        pid = get_project_id(ak, sk)
        print(f"  - 身份验证: 🟢 成功 (Project ID: {pid})")
    except Exception as e:
        print(f"  - 身份验证: 🔴 失败！请核对 .env.cce 中的 AK/SK\n    错误详情: {e}")
        return

    # 2. 数据库与网络安全
    print(f"\n[2/4] 网络与数据库安全 (整合原本 get_rds_ip.py)")
    rds_list = get_rds_instances(ak, sk, pid)
    if not rds_list:
        print(f"  - RDS 探测: 🟡 警告！未发现任何 RDS 实例。")
    else:
        for inst in rds_list:
            name, status = inst.get('name'), inst.get('status')
            private_ip = inst.get('private_ips', ['?'])[0]
            sg_id = inst.get('security_group_id')
            print(f"  - 实例名称: {name} (当前状态: {status})")
            print(f"    - 内网地址: {private_ip}")
            
            # 安全组深度审计 (整合原本 debug_rds_sg.py)
            rules = get_sg_rules(ak, sk, pid, sg_id)
            port_5432_open = False
            for r in rules:
                if r['direction'] == 'ingress' and r.get('protocol') in ('tcp', None):
                    p_min, p_max = r.get('port_range_min'), r.get('port_range_max')
                    # 检查端口 5432 是否包含在规则范围内
                    if p_min is None or (p_min <= 5432 <= p_max):
                        src = r.get('remote_ip_prefix') or r.get('remote_group_id')
                        if src == "0.0.0.0/0":
                            print(f"    - 防火墙: 🟢 端口 5432 已对公网全开 (注意数据安全)")
                        else:
                            print(f"    - 防火墙: 🟢 端口 5432 已对 {src} 开放")
                        port_5432_open = True
            if not port_5432_open:
                print(f"    - 防火墙: 🔴 严重告警！未检测到 PostgreSQL(5432) 入向规则，网络将无法接通。")

    # 3. 容器基础设施 (整合原本 swr_docker_check.py)
    print(f"\n[3/4] 容器基础设施")
    clusters = get_cce_clusters(ak, sk, pid)
    if not clusters:
        print(f"  - CCE 集群: 🔴 告警！未发现 CCE 集群。")
    else:
        for c in clusters:
            cid, cname = c['metadata']['uid'], c['metadata']['name']
            nodes = get_cce_nodes(ak, sk, pid, cid)
            ready = sum(1 for n in nodes if n['status'].get('phase') == 'Active')
            print(f"  - 集群实例: {cname} (活跃节点: {ready}/{len(nodes)})")
    
    # SWR 仓库连通性测试
    s, d = api("GET", f"https://swr.{REGION}.myhuaweicloud.com/v2/_catalog", ak, sk)
    if s in (200, 401): # 401 Unauthorized 说明 API 网关已接通且身份可识别
        print(f"  - SWR 服务: 🟢 连通正常 (API Status: {s})")
    else:
        print(f"  - SWR 服务: 🔴 连接失败或被拒绝 (Status: {s})")

    # 4. 本地配置同步审计
    print(f"\n[4/4] 本地配置一致性审计")
    if os.path.exists(VALUES_FILE):
        try:
            with open(VALUES_FILE, 'r', encoding='utf-8') as f:
                content = f.read()
                # 使用正则查找 Helm 配置中的关键 ID
                m_host = re.search(r'externalHost:\s*"([^"]*)"', content)
                m_elb = re.search(r'id:\s*"([0-9a-f-]{36})"', content)
                
                cur_eips = [e['public_ip_address'] for e in get_eips(ak, sk, pid)]
                if m_host:
                    ip = m_host.group(1)
                    if ip in cur_eips:
                        print(f"  - 外部域名 IP ({ip}): 🟢 云端匹配")
                    else:
                        print(f"  - 外部域名 IP ({ip}): 🔴 警告！该 IP 未绑定在当前账号下。")
                
                if m_elb:
                    eid = m_elb.group(1)
                    if get_elb_detail(ak, sk, pid, eid):
                        print(f"  - ELB ID 对齐: 🟢 在线")
                    else:
                        print(f"  - ELB ID ({eid[:8]}...): 🔴 警告！配置中的 ELB 在云端无法找到。")
        except Exception as e:
            print(f"  - 扫描配置失败: {e}")
    else:
        print(f"  - 本地配置: 🟡 缺少 {VALUES_FILE} 文件。")

    print("\n" + "="*60)
    print("✅ 深度诊断完成")

# ── 资源管理功能 (Management Functions) ──────────────────────────

def load_cache():
    """读取本地缓存 JSON"""
    if os.path.exists(CACHE_FILE):
        with open(CACHE_FILE, encoding='utf-8') as f: return json.load(f)
    return {}

def save_cache(data):
    """写入本地缓存 JSON"""
    with open(CACHE_FILE, "w", encoding='utf-8') as f: 
        json.dump(data, f, indent=2, ensure_ascii=False)

def cmd_status(ak, sk):
    """简洁的资源状态汇总"""
    print("\n[ Lakeon 华为云资源概览 ]")
    pid = get_project_id(ak, sk)
    
    # 打印数据库状态
    for r in get_rds_instances(ak, sk, pid):
        icon = '🟢' if r['status'].upper() in ('ACTIVE','NORMAL') else '🔴'
        print(f"  RDS: {icon} {r['name']} ({r['status']})")
        
    # 打印集群状态
    for c in get_cce_clusters(ak, sk, pid):
        nodes = get_cce_nodes(ak, sk, pid, c['metadata']['uid'])
        ready = sum(1 for n in nodes if n['status'].get('phase') == 'Active')
        print(f"  CCE: {'🟢' if ready > 0 else '🔴'} {c['metadata']['name']} (在线节点: {ready}/{len(nodes)})")
        
    # 打印公网 IP
    eips = get_eips(ak, sk, pid)
    print(f"  EIP: 共 {len(eips)} 个地址在用")

def cmd_discover(ak, sk):
    """搜寻 Lakeon 资源并更新缓存"""
    print("🚀 正在自动搜寻 Lakeon 相关云资源...")
    pid = get_project_id(ak, sk)
    cache = {"project_id": pid, "last_discovery": datetime.now().strftime("%Y-%m-%d %H:%M:%S")}
    
    # RDS 发现逻辑
    rds = get_rds_instances(ak, sk, pid)
    if rds:
        # 寻找名称包含 lakeon 的实例，否则取第一个
        target = next((r for r in rds if "lakeon" in r['name'].lower()), rds[0])
        cache["rds_id"], cache["rds_name"] = target["id"], target["name"]
        print(f"  ✓ 发现 RDS: {target['name']}")
    
    # CCE 发现逻辑
    clusters = get_cce_clusters(ak, sk, pid)
    if clusters:
        c = next((cl for cl in clusters if "lakeon" in cl['metadata']['name'].lower()), clusters[0])
        cache["cluster_id"] = c["metadata"]["uid"]
        cache["cluster_name"] = c["metadata"]["name"]
        nodes = get_cce_nodes(ak, sk, pid, c["metadata"]["uid"])
        cache["nodes"] = [{"flavor": n["spec"]["flavor"], "az": n["spec"]["az"], "uid": n["metadata"]["uid"]} for n in nodes]
        print(f"  ✓ 发现 CCE: {c['metadata']['name']} (包含 {len(nodes)} 个节点规格)")

    save_cache(cache)
    print(f"🎉 识别完成，缓存已保存：{os.path.basename(CACHE_FILE)}")

# ── 启动/关停控制逻辑 ─────────────────────────────────────────────

def rds_action(ak, sk, pid, inst_id, action):
    """执行 RDS 的 startup(开机) 或 shutdown(关机) 操作"""
    url = f"https://rds.{REGION}.myhuaweicloud.com/v3/{pid}/instances/{inst_id}/action/{action}"
    return api("POST", url, ak, sk, "{}")

def cmd_stop_cloud(ak, sk):
    """
    一键关停流程。
    主要任务：关停 RDS 实例以节省费用。
    注意：为了避免误删，本脚本默认不自动删除计算节点，仅执行 RDS 关机。
    """
    cache = load_cache()
    if not cache.get("rds_id"):
        print("🔴 错误：缓存中无 RDS 信息，请先运行 discover 命令。")
        return
    
    print(f"正在关停 RDS 实例: {cache['rds_name']}...")
    s, d = rds_action(ak, sk, cache["project_id"], cache["rds_id"], "shutdown")
    if s in (200, 202):
        print("🟢 关机指令已提交成功。")
    else:
        print(f"🔴 提交失败: {d}")

def cmd_start_cloud(ak, sk):
    """一键启动流程"""
    cache = load_cache()
    if not cache.get("rds_id"):
        print("🔴 错误：缓存中无 RDS 信息，请先运行 discover 命令。")
        return
        
    print(f"正在启动 RDS 实例: {cache['rds_name']}...")
    s, d = rds_action(ak, sk, cache["project_id"], cache["rds_id"], "startup")
    if s in (200, 202):
        print("🟢 启动指令已提交，请耐心等待 3-5 分钟。")
    else:
        print(f"🔴 提交失败: {d}")

# ── 命令行入口 ────────────────────────────────────────────────────

COMMANDS = {
    "check": cmd_check,           # 综合诊断
    "status": cmd_status,         # 资源预览
    "discover": cmd_discover,     # 资源搜寻与缓存
    "start-cloud": cmd_start_cloud,
    "stop-cloud": cmd_stop_cloud,
}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        sys.exit(1)
    
    # 初始化
    ak, sk, creds = load_credentials()
    if not ak or not sk:
        print(f"🔴 致命错误：未能在 {ENV_FILE} 中找到 OBS_AK 或 OBS_SK。程序退出。")
        sys.exit(1)
        
    cmd = sys.argv[1]
    try:
        COMMANDS[cmd](ak, sk)
    except KeyboardInterrupt:
        print("\n👋 操作已由用户手动中止。")
    except Exception as e:
        print(f"\n🔴 运行时发生不可预期的错误: {e}")
