#!/usr/bin/env python3
"""
Lakeon 华为云资源管理 - 极致省钱模式

用法:
  python3 hwcloud.py discover      # 发现并缓存资源 ID
  python3 hwcloud.py stop-cloud    # 关停: 删 ELB + 释放 EIP + 节点缩0 + 停 RDS
  python3 hwcloud.py start-cloud   # 启动: 启 RDS + 扩节点 + 建 ELB + 分配 EIP
  python3 hwcloud.py status        # 查看资源状态
  python3 hwcloud.py wait          # 等待 RDS 和节点就绪
"""

import hashlib, hmac, json, os, re, sys, time
import urllib.request, urllib.parse, urllib.error
from datetime import datetime, timezone

REGION = "cn-north-4"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_FILE = os.path.join(SCRIPT_DIR, ".env.cce")
CACHE_FILE = os.path.join(SCRIPT_DIR, ".lakeon-cloud.json")
VALUES_FILE = os.path.join(SCRIPT_DIR, "values-cce.yaml")


# ── Credentials ──────────────────────────────────────────────────

def load_credentials():
    creds = {}
    with open(ENV_FILE) as f:
        for line in f:
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                line = line.removeprefix("export ").strip()
                key, val = line.split("=", 1)
                creds[key.strip()] = val.strip().strip("'\"")
    return creds.get("OBS_AK"), creds.get("OBS_SK"), creds


# ── HWC API Signing ──────────────────────────────────────────────

def _hmac256(key, msg):
    if isinstance(key, str): key = key.encode()
    if isinstance(msg, str): msg = msg.encode()
    return hmac.new(key, msg, hashlib.sha256).digest()

def _sha256(data):
    if isinstance(data, str): data = data.encode()
    return hashlib.sha256(data).hexdigest()

def api(method, url, ak, sk, body="", timeout=30):
    parsed = urllib.parse.urlparse(url)
    host = parsed.hostname
    path = parsed.path or "/"
    if not path.endswith("/"):
        path += "/"
    query = parsed.query or ""
    if query:
        params = urllib.parse.parse_qsl(query, keep_blank_values=True)
        params.sort(key=lambda x: x[0])
        query = urllib.parse.urlencode(params)

    now = datetime.now(timezone.utc)
    ts = now.strftime("%Y%m%dT%H%M%SZ")
    ds = now.strftime("%Y%m%d")

    base = host.replace(".myhuaweicloud.com", "")
    parts = base.split(".")
    svc, rgn = (parts[0], parts[1]) if len(parts) >= 2 else (parts[0], "")

    sh = "host;x-sdk-date"
    ch = f"host:{host}\nx-sdk-date:{ts}\n"
    cr = f"{method}\n{path}\n{query}\n{ch}\n{sh}\n{_sha256(body)}"
    scope = f"{ds}/{rgn}/{svc}/sdk_request"
    sts = f"SDK-HMAC-SHA256\n{ts}\n{scope}\n{_sha256(cr)}"

    k = _hmac256(_hmac256(_hmac256(_hmac256(f"SDK{sk}".encode(), ds), rgn), svc), "sdk_request")
    sig = hmac.new(k, sts.encode(), hashlib.sha256).hexdigest()

    headers = {
        "Host": host, "X-Sdk-Date": ts,
        "Authorization": f"SDK-HMAC-SHA256 Credential={ak}/{scope}, SignedHeaders={sh}, Signature={sig}",
    }
    if body:
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=body.encode() if body else None, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read().decode())
        except: return e.code, {"error": str(e)}
    except Exception as e:
        return 0, {"error": str(e)}


def _get_cluster_eip():
    """Extract CCE cluster API EIP from kubeconfig to avoid releasing it."""
    kc = os.environ.get("KUBECONFIG", os.path.expanduser("~/.kube/cce-lakeon-config"))
    try:
        with open(kc) as f:
            m = re.search(r'server:\s*https?://([0-9.]+)', f.read())
            return m.group(1) if m else None
    except: return None


# ── Discovery ────────────────────────────────────────────────────

def get_project_id(ak, sk):
    s, d = api("GET", f"https://iam.myhuaweicloud.com/v3/projects?name={REGION}", ak, sk)
    if s == 200 and d.get("projects"):
        return d["projects"][0]["id"]
    raise Exception(f"获取 Project ID 失败: {s} {d}")

def get_cce_cluster(ak, sk, pid):
    s, d = api("GET", f"https://cce.{REGION}.myhuaweicloud.com/api/v3/projects/{pid}/clusters", ak, sk)
    if s == 200:
        for c in d.get("items", []):
            if "lakeon" in c["metadata"]["name"].lower():
                return c["metadata"]["uid"], c["metadata"]["name"]
        if d.get("items"):
            c = d["items"][0]
            return c["metadata"]["uid"], c["metadata"]["name"]
    raise Exception(f"获取 CCE 集群失败: {s} {d}")

def get_nodes(ak, sk, pid, cid):
    s, d = api("GET", f"https://cce.{REGION}.myhuaweicloud.com/api/v3/projects/{pid}/clusters/{cid}/nodes", ak, sk)
    if s != 200: return []
    nodes = []
    for n in d.get("items", []):
        spec = n["spec"]
        nodes.append({
            "uid": n["metadata"]["uid"],
            "name": n["metadata"]["name"],
            "flavor": spec["flavor"],
            "az": spec["az"],
            "os": spec.get("os", "Huawei Cloud EulerOS 2.0"),
            "root_volume": spec.get("rootVolume", {"volumetype": "GPSSD", "size": 50}),
            "data_volumes": spec.get("dataVolumes", [{"volumetype": "GPSSD", "size": 100}]),
            "phase": n.get("status", {}).get("phase", ""),
            "server_id": n.get("status", {}).get("serverId", ""),
            "login": spec.get("login", {}),
            "runtime": spec.get("runtime", {"name": "containerd"}),
            "subnet_id": spec.get("nodeNicSpec", {}).get("primaryNic", {}).get("subnetId", ""),
        })
    return nodes

def get_node_ready_count(ak, sk, pid, cid):
    nodes = get_nodes(ak, sk, pid, cid)
    ready = sum(1 for n in nodes if n["phase"] == "Active")
    return len(nodes), ready

def get_rds(ak, sk, pid):
    s, d = api("GET", f"https://rds.{REGION}.myhuaweicloud.com/v3/{pid}/instances", ak, sk)
    if s == 200:
        for inst in d.get("instances", []):
            if "lakeon" in inst.get("name", "").lower():
                return inst["id"], inst["name"], inst["status"]
        if d.get("instances"):
            i = d["instances"][0]
            return i["id"], i["name"], i["status"]
    raise Exception(f"获取 RDS 失败: {s} {d}")

def get_elb(ak, sk, pid, elb_id):
    s, d = api("GET", f"https://elb.{REGION}.myhuaweicloud.com/v3/{pid}/elb/loadbalancers/{elb_id}", ak, sk)
    if s == 200:
        lb = d.get("loadbalancer", d)
        return lb
    return None

def get_elb_listeners(ak, sk, pid, elb_id):
    s, d = api("GET", f"https://elb.{REGION}.myhuaweicloud.com/v3/{pid}/elb/listeners?loadbalancer_id={elb_id}", ak, sk)
    if s == 200:
        return d.get("listeners", [])
    return []

def get_eips(ak, sk, pid):
    s, d = api("GET", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/publicips?limit=100", ak, sk)
    if s == 200:
        return d.get("publicips", [])
    return []


# ── ELB Operations ───────────────────────────────────────────────

def delete_elb_listener(ak, sk, pid, lid):
    return api("DELETE", f"https://elb.{REGION}.myhuaweicloud.com/v3/{pid}/elb/listeners/{lid}", ak, sk)

def delete_elb_pool(ak, sk, pid, pool_id):
    return api("DELETE", f"https://elb.{REGION}.myhuaweicloud.com/v3/{pid}/elb/pools/{pool_id}", ak, sk)

def delete_elb(ak, sk, pid, elb_id):
    return api("DELETE", f"https://elb.{REGION}.myhuaweicloud.com/v3/{pid}/elb/loadbalancers/{elb_id}", ak, sk)

def create_elb(ak, sk, pid, config):
    body = json.dumps({"loadbalancer": config})
    return api("POST", f"https://elb.{REGION}.myhuaweicloud.com/v3/{pid}/elb/loadbalancers", ak, sk, body)


# ── EIP Operations ───────────────────────────────────────────────

def release_eip(ak, sk, pid, eip_id):
    return api("DELETE", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/publicips/{eip_id}", ak, sk)

def create_eip(ak, sk, pid, config):
    body = json.dumps(config)
    return api("POST", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/publicips", ak, sk, body)

def bind_eip(ak, sk, pid, eip_id, port_id):
    body = json.dumps({"publicip": {"port_id": port_id}})
    return api("PUT", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/publicips/{eip_id}", ak, sk, body)


# ── Node Operations ──────────────────────────────────────────────

def delete_node(ak, sk, pid, cid, node_uid):
    return api("DELETE", f"https://cce.{REGION}.myhuaweicloud.com/api/v3/projects/{pid}/clusters/{cid}/nodes/{node_uid}", ak, sk)

def create_node(ak, sk, pid, cid, node_spec, node_password=""):
    spec = {
        "flavor": node_spec["flavor"],
        "az": node_spec["az"],
        "os": node_spec.get("os", "Huawei Cloud EulerOS 2.0"),
        "rootVolume": node_spec.get("root_volume", {"volumetype": "GPSSD", "size": 50}),
        "dataVolumes": node_spec.get("data_volumes", [{"volumetype": "GPSSD", "size": 100}]),
        "runtime": node_spec.get("runtime", {"name": "containerd"}),
        "nodeNicSpec": {"primaryNic": {"subnetId": node_spec.get("subnet_id", "")}},
        "extendParam": {"chargingMode": 0},
    }
    if node_password:
        spec["login"] = {"userPassword": {"username": "root", "password": node_password}}
    else:
        spec["login"] = node_spec.get("login", {"userPassword": {"username": "root", "password": "Admin@2026"}})
    body = json.dumps({"kind": "Node", "apiVersion": "v3", "metadata": {}, "spec": spec})
    return api("POST", f"https://cce.{REGION}.myhuaweicloud.com/api/v3/projects/{pid}/clusters/{cid}/nodes", ak, sk, body)

def rds_action(ak, sk, pid, inst_id, action):
    return api("POST", f"https://rds.{REGION}.myhuaweicloud.com/v3/{pid}/instances/{inst_id}/action/{action}",
               ak, sk, "{}")


# ── Cache ────────────────────────────────────────────────────────

def load_cache():
    if os.path.exists(CACHE_FILE):
        with open(CACHE_FILE) as f: return json.load(f)
    return {}

def save_cache(data):
    with open(CACHE_FILE, "w") as f: json.dump(data, f, indent=2, ensure_ascii=False)


# ── Update values-cce.yaml ──────────────────────────────────────

def update_values(elb_id, eip):
    with open(VALUES_FILE) as f:
        content = f.read()
    # Update proxy.externalHost
    content = re.sub(r'(externalHost:\s*)"[^"]*"', f'\\1"{eip}"', content)
    # Update all elb.id occurrences
    content = re.sub(r'(id:\s*)"[0-9a-f-]+"', f'\\1"{elb_id}"', content)
    with open(VALUES_FILE, "w") as f:
        f.write(content)
    print(f"  已更新 {VALUES_FILE}")
    print(f"    externalHost: {eip}")
    print(f"    elb.id: {elb_id}")


# ── Commands ─────────────────────────────────────────────────────

def cmd_discover(ak, sk):
    print("发现华为云资源...\n")
    pid = get_project_id(ak, sk)
    print(f"  Project:  {pid}")
    cid, cname = get_cce_cluster(ak, sk, pid)
    print(f"  CCE:      {cname}")
    nodes = get_nodes(ak, sk, pid, cid)
    for n in nodes:
        print(f"  节点:     {n['name']} ({n['flavor']}, {n['phase']})")
    rid, rname, rstatus = get_rds(ak, sk, pid)
    print(f"  RDS:      {rname} ({rstatus})")

    # ELB - read from values-cce.yaml
    with open(VALUES_FILE) as f:
        m = re.search(r'id:\s*"([0-9a-f-]+)"', f.read())
    elb_id = m.group(1) if m else None
    elb_config = None
    if elb_id:
        lb = get_elb(ak, sk, pid, elb_id)
        if lb:
            elb_config = {
                "name": lb.get("name", "lakeon-elb"),
                "vip_subnet_cidr_id": lb.get("vip_subnet_cidr_id"),
                "elb_virsubnet_ids": lb.get("elb_virsubnet_ids", []),
                "availability_zone_list": lb.get("availability_zone_list", []),
                "description": lb.get("description", "Lakeon ELB"),
                "guaranteed": lb.get("guaranteed", False),
                "vpc_id": lb.get("vpc_id"),
            }
            print(f"  ELB:      {lb.get('name')} ({elb_id[:12]}...)")

    # EIPs — exclude the CCE cluster API EIP
    cluster_eip = _get_cluster_eip()
    eips = get_eips(ak, sk, pid)
    eip_configs = []
    for e in eips:
        if e.get("status") == "ACTIVE" and e.get("public_ip_address") != cluster_eip:
            eip_configs.append({
                "ip": e.get("public_ip_address"),
                "id": e["id"],
                "type": e.get("type", "5_bgp"),
                "bandwidth_id": e.get("bandwidth_id"),
                "bandwidth_size": e.get("bandwidth_size", 5),
                "bandwidth_share_type": e.get("bandwidth_share_type", "PER"),
                "bandwidth_name": e.get("bandwidth_name", "lakeon-bw"),
            })
    for e in eip_configs:
        print(f"  EIP:      {e['ip']} ({e['id'][:12]}...)")
    if cluster_eip:
        print(f"  EIP:      {cluster_eip} (集群管理, 保留)")

    cache = {
        "project_id": pid, "cluster_id": cid, "cluster_name": cname,
        "nodes": nodes, "rds_id": rid, "rds_name": rname,
        "elb_id": elb_id, "elb_config": elb_config, "eip_configs": eip_configs,
    }
    save_cache(cache)
    print(f"\n  已缓存到 .lakeon-cloud.json")
    return cache


def cmd_stop_cloud(ak, sk):
    cache = load_cache()
    if not cache.get("project_id"):
        cache = cmd_discover(ak, sk)
    pid = cache["project_id"]
    cid = cache["cluster_id"]

    # Refresh and save current node specs for recreation
    nodes = get_nodes(ak, sk, pid, cid)
    cache["nodes"] = nodes
    save_cache(cache)

    print("\n=== 关停华为云资源 ===\n")

    # 1. Delete ELB (listeners should be gone after helm uninstall)
    elb_id = cache.get("elb_id")
    if elb_id:
        lb = get_elb(ak, sk, pid, elb_id)
        if lb:
            # Save config for recreation
            cache["elb_config"] = {
                "name": lb.get("name", "lakeon-elb"),
                "vip_subnet_cidr_id": lb.get("vip_subnet_cidr_id"),
                "elb_virsubnet_ids": lb.get("elb_virsubnet_ids", []),
                "availability_zone_list": lb.get("availability_zone_list", []),
                "description": "Lakeon ELB (auto-recreated)",
                "guaranteed": lb.get("guaranteed", False),
                "vpc_id": lb.get("vpc_id"),
            }
            save_cache(cache)

            # Clean remaining listeners/pools
            listeners = get_elb_listeners(ak, sk, pid, elb_id)
            for l in listeners:
                for pool_id in [l.get("default_pool_id")] + [r.get("redirect_pool_id", "") for r in l.get("rules", [])]:
                    if pool_id:
                        delete_elb_pool(ak, sk, pid, pool_id)
                delete_elb_listener(ak, sk, pid, l["id"])

            print(f"  删除 ELB: {elb_id[:12]}...")
            s, r = delete_elb(ak, sk, pid, elb_id)
            print(f"    {'✓' if s in (200,204) else '✗'} {s}")
        else:
            print(f"  ELB {elb_id[:12]}... 已不存在")

    # 2. Release EIPs
    for e in cache.get("eip_configs", []):
        print(f"  释放 EIP: {e['ip']} ({e['id'][:12]}...)")
        s, r = release_eip(ak, sk, pid, e["id"])
        print(f"    {'✓' if s in (200,204) else '✗'} {s}")

    # 3. Delete nodes
    for n in nodes:
        print(f"  删除节点: {n['name']} ({n['flavor']})")
        s, r = delete_node(ak, sk, pid, cid, n["uid"])
        print(f"    {'✓' if s in (200,204) else '✗'} {s}")

    # 4. Stop RDS
    rid = cache.get("rds_id")
    if rid:
        print(f"  关停 RDS: {cache.get('rds_name')}")
        s, r = rds_action(ak, sk, pid, rid, "shutdown")
        ok = s in (200, 202, 204) or "SHUTTING" in str(r)
        print(f"    {'✓' if ok else '✗'} {s}")

    print(f"\n✅ 关停请求已提交")
    print(f"   节约: ELB ¥12/天 + EIP ¥10/天 + 节点 ¥72/天 + RDS ¥17/天 ≈ ¥111/天")


def cmd_start_cloud(ak, sk):
    cache = load_cache()
    if not cache.get("project_id"):
        cache = cmd_discover(ak, sk)
    pid = cache["project_id"]
    cid = cache["cluster_id"]

    print("\n=== 启动华为云资源 ===\n")

    # 1. Start RDS
    rid = cache.get("rds_id")
    if rid:
        print(f"  启动 RDS: {cache.get('rds_name')}")
        s, r = rds_action(ak, sk, pid, rid, "startup")
        ok = s in (200, 202, 204) or "ACTIVE" in str(r)
        print(f"    {'✓' if ok else '✗'} {s}")

    # 2. Create nodes
    saved_nodes = cache.get("nodes", [])
    node_pw = cache.get("_node_password", "")
    for n in saved_nodes:
        print(f"  创建节点: {n['flavor']} ({n['az']})")
        s, r = create_node(ak, sk, pid, cid, n, node_pw)
        print(f"    {'✓' if s in (200,201) else '✗'} {s}")
        if s not in (200, 201):
            print(f"    {json.dumps(r, ensure_ascii=False)[:300]}")

    # 3. Wait for RDS + nodes
    print(f"\n  等待资源就绪（RDS ~3-5min, 节点 ~5-10min）...")
    expected = len(saved_nodes)
    rds_ok = nodes_ok = False
    for i in range(90):
        if not rds_ok:
            try:
                _, _, st = get_rds(ak, sk, pid)
                if st.upper() in ("ACTIVE", "NORMAL"):
                    print(f"  ✓ RDS 就绪")
                    rds_ok = True
                else:
                    if i % 6 == 0: print(f"  ⏳ RDS: {st}")
            except: pass
        if not nodes_ok:
            try:
                _, ready = get_node_ready_count(ak, sk, pid, cid)
                if ready >= expected:
                    print(f"  ✓ 节点就绪: {ready}/{expected}")
                    nodes_ok = True
                else:
                    if i % 6 == 0: print(f"  ⏳ 节点: {ready}/{expected}")
            except: pass
        if rds_ok and nodes_ok:
            break
        time.sleep(10)

    if not (rds_ok and nodes_ok):
        print(f"\n⚠ 等待超时，请检查华为云控制台后重试")
        return

    # 4. Create new ELB
    elb_cfg = cache.get("elb_config")
    new_elb_id = None
    if elb_cfg:
        print(f"\n  创建 ELB...")
        # Clean config for creation
        create_cfg = {k: v for k, v in elb_cfg.items() if v is not None}
        s, r = create_elb(ak, sk, pid, create_cfg)
        if s in (200, 201):
            lb = r.get("loadbalancer", r)
            new_elb_id = lb["id"]
            vip_port_id = lb.get("vip_port_id")
            print(f"    ✓ ELB: {new_elb_id[:12]}...")
            cache["elb_id"] = new_elb_id
        else:
            print(f"    ✗ ELB 创建失败: {s} {json.dumps(r, ensure_ascii=False)[:300]}")
            return
    else:
        print(f"\n  ⚠ 无 ELB 配置缓存，跳过 ELB 创建")
        return

    # 5. Allocate new EIPs and bind to ELB
    new_eip = None
    new_eip_configs = []
    for i, old in enumerate(cache.get("eip_configs", [])):
        print(f"  分配 EIP #{i+1}...")
        eip_req = {
            "publicip": {"type": old.get("type", "5_bgp")},
            "bandwidth": {
                "name": old.get("bandwidth_name", f"lakeon-bw-{i+1}"),
                "size": old.get("bandwidth_size", 5),
                "share_type": old.get("bandwidth_share_type", "PER"),
            },
        }
        s, r = create_eip(ak, sk, pid, eip_req)
        if s in (200, 201):
            eip = r.get("publicip", r)
            eip_id = eip["id"]
            eip_ip = eip.get("public_ip_address", "分配中...")
            print(f"    ✓ EIP: {eip_ip} ({eip_id[:12]}...)")

            # Bind first EIP to ELB
            if i == 0 and vip_port_id:
                print(f"  绑定 EIP → ELB...")
                bs, br = bind_eip(ak, sk, pid, eip_id, vip_port_id)
                if bs == 200:
                    eip_ip = br.get("publicip", {}).get("public_ip_address", eip_ip)
                    print(f"    ✓ 已绑定: {eip_ip}")
                    new_eip = eip_ip
                else:
                    print(f"    ✗ 绑定失败: {bs}")

            new_eip_configs.append({
                "ip": eip_ip, "id": eip_id,
                "type": old.get("type", "5_bgp"),
                "bandwidth_size": old.get("bandwidth_size", 5),
                "bandwidth_share_type": old.get("bandwidth_share_type", "PER"),
                "bandwidth_name": old.get("bandwidth_name", f"lakeon-bw-{i+1}"),
            })
        else:
            print(f"    ✗ 分配失败: {s} {json.dumps(r, ensure_ascii=False)[:200]}")

    cache["eip_configs"] = new_eip_configs
    save_cache(cache)

    # 6. Update values-cce.yaml
    if new_elb_id and new_eip:
        print(f"\n  更新配置文件...")
        update_values(new_elb_id, new_eip)

    print(f"\n✅ 云资源已就绪")
    if new_eip:
        print(f"\n  新 IP: {new_eip}")
        print(f"  接下来运行 start.sh 完成 K8s 部署")


def cmd_status(ak, sk):
    cache = load_cache()
    if not cache.get("project_id"):
        cache = cmd_discover(ak, sk)
    pid, cid = cache["project_id"], cache["cluster_id"]

    print("\n=== 华为云资源状态 ===\n")

    # Nodes
    nodes = get_nodes(ak, sk, pid, cid)
    total = len(nodes)
    for n in nodes:
        icon = "🟢" if n["phase"] == "Active" else "🔴"
        print(f"  节点 {n['name']}: {icon} {n['flavor']} ({n['phase']})")

    # RDS
    _, rname, rst = get_rds(ak, sk, pid)
    print(f"  RDS {rname}: {'🟢' if rst.upper() in ('ACTIVE','NORMAL') else '🔴'} {rst}")

    # ELB
    elb_id = cache.get("elb_id")
    if elb_id:
        lb = get_elb(ak, sk, pid, elb_id)
        if lb:
            print(f"  ELB: 🟢 {lb.get('name')} ({lb.get('operating_status', 'unknown')})")
        else:
            print(f"  ELB: 🔴 不存在 ({elb_id[:12]}...)")
    else:
        print(f"  ELB: 未配置")

    # EIPs
    eips = get_eips(ak, sk, pid)
    for e in eips:
        print(f"  EIP: {e.get('public_ip_address')} — {e.get('status')}")

    running = total > 0 and rst.upper() in ("ACTIVE", "NORMAL")
    print(f"\n  {'🟢 运行中' if running else '🔴 已关停'}")


# ── Main ─────────────────────────────────────────────────────────

COMMANDS = {
    "discover": cmd_discover,
    "stop-cloud": cmd_stop_cloud,
    "start-cloud": cmd_start_cloud,
    "status": cmd_status,
}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        sys.exit(1)
    ak, sk, all_creds = load_credentials()
    if not ak or not sk:
        print(f"错误: 请在 {ENV_FILE} 中配置 OBS_AK 和 OBS_SK")
        sys.exit(1)
    # Store node password in cache for start-cloud
    if sys.argv[1] in ("discover", "stop-cloud"):
        cache = load_cache()
        pw = all_creds.get("CCE_NODE_PASSWORD", "")
        if pw:
            cache["_node_password"] = pw
            save_cache(cache)
    COMMANDS[sys.argv[1]](ak, sk)
