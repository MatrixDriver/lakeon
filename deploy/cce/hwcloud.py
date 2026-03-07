#!/usr/bin/env python3
"""
Lakeon 华为云资源管理 - 极致省钱模式

资源: 1x ac7.2xlarge.2 节点 + 共享ELB + 按流量EIP + RDS
运行成本: ~¥89/天 (CCE ¥24 + 节点 ¥48 + RDS ¥17)

两种关停模式:
  默认模式 — 保留 CCE 集群 + 集群 EIP（¥24/天），省 ~¥65/天
  --full   — 删除一切（仅保留 OBS/SWR），省 ~¥89/天
             启动时需手动绑 EIP 到集群并更新 kubeconfig

用法:
  python3 hwcloud.py discover        # 发现并缓存资源 ID
  python3 hwcloud.py stop-cloud      # 关停（保留集群 EIP）
  python3 hwcloud.py stop-cloud --full  # 关停（释放所有 EIP）
  python3 hwcloud.py start-cloud     # 启动
  python3 hwcloud.py status          # 查看资源状态
  python3 hwcloud.py list-resources  # 输出所有资源 JSON（供管理台使用）
"""

import hashlib, hmac, json, os, re, sys, time
import urllib.request, urllib.parse, urllib.error
from datetime import datetime, timezone

REGION = "cn-south-1"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_FILE = os.path.join(SCRIPT_DIR, ".env.cce")
CACHE_FILE = os.path.join(SCRIPT_DIR, ".lakeon-cloud.json")
VALUES_FILE = os.path.join(SCRIPT_DIR, "values-cce.yaml")


# ── Credentials ──────────────────────────────────────────────────

def load_credentials():
    creds = {}
    with open(ENV_FILE, encoding='utf-8') as f:
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
        with open(kc, encoding='utf-8') as f:
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
                return inst["id"], inst["name"], inst["status"], inst.get("private_ips", [""])[0]
        if d.get("instances"):
            i = d["instances"][0]
            return i["id"], i["name"], i["status"], i.get("private_ips", [""])[0]
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
    # Standard CCE Node Spec
    spec = {
        "flavor": node_spec.get("flavor", "s6.xlarge.2"),
        "az": node_spec["az"],
        "os": "Huawei Cloud EulerOS 2.9", # 2.9 is a safe standard
        "rootVolume": {"volumetype": "GPSSD", "size": 50},
        "dataVolumes": [{"volumetype": "GPSSD", "size": 100}],
        "runtime": {"name": "containerd"},
        "nodeNicSpec": {"primaryNic": {"subnetId": node_spec.get("subnet_id", "2f556317-04ce-4df6-9472-3bd98b3fd49e")}},
        "extendParam": {"chargingMode": 0},
        "login": {"sshKey": "lakeon-key"},
        "count": 1
    }
    body = json.dumps({"kind": "Node", "apiVersion": "v3", "metadata": {}, "spec": spec})
    return api("POST", f"https://cce.{REGION}.myhuaweicloud.com/api/v3/projects/{pid}/clusters/{cid}/nodes", ak, sk, body)

def rds_action(ak, sk, pid, inst_id, action):
    return api("POST", f"https://rds.{REGION}.myhuaweicloud.com/v3/{pid}/instances/{inst_id}/action/{action}",
               ak, sk, "{}")


# ── Cache ────────────────────────────────────────────────────────

def load_cache():
    if os.path.exists(CACHE_FILE):
        with open(CACHE_FILE, encoding='utf-8') as f: return json.load(f)
    return {}

def save_cache(data):
    with open(CACHE_FILE, "w", encoding='utf-8') as f: json.dump(data, f, indent=2, ensure_ascii=False)


# ── Update values-cce.yaml ──────────────────────────────────────

def update_values(elb_id, eip):
    with open(VALUES_FILE, encoding='utf-8') as f:
        content = f.read()
    # Update proxy.externalHost
    content = re.sub(r'(externalHost:\s*)"[^"]*"', f'\\1"{eip}"', content)
    # Update all elb.id occurrences
    content = re.sub(r'(id:\s*)"[0-9a-f-]+"', f'\\1"{elb_id}"', content)
    with open(VALUES_FILE, "w", encoding='utf-8') as f:
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
    rid, rname, rstatus, rip = get_rds(ak, sk, pid)
    print(f"  RDS:      {rname} ({rstatus}) — {rip}")

    # ELB - read from values-cce.yaml
    with open(VALUES_FILE, encoding='utf-8') as f:
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

    # EIPs
    cluster_eip = _get_cluster_eip()
    eips = get_eips(ak, sk, pid)
    eip_configs = []
    for e in eips:
        if e.get("status") == "ACTIVE":
            is_cluster = e.get("public_ip_address") == cluster_eip
            eip_configs.append({
                "ip": e.get("public_ip_address"),
                "id": e["id"],
                "type": e.get("type", "5_bgp"),
                "bandwidth_id": e.get("bandwidth_id"),
                "bandwidth_size": e.get("bandwidth_size", 5),
                "bandwidth_share_type": e.get("bandwidth_share_type", "PER"),
                "bandwidth_name": e.get("bandwidth_name", "lakeon-bw"),
                "is_cluster_eip": is_cluster,
            })
            label = " (集群管理)" if is_cluster else ""
            print(f"  EIP:      {e.get('public_ip_address')}{label} ({e['id'][:12]}...)")

    # Merge into existing cache — don't overwrite saved node/elb specs if current resources are down
    cache = load_cache()
    cache.update({
        "project_id": pid, "cluster_id": cid, "cluster_name": cname,
        "rds_id": rid, "rds_name": rname, "elb_id": elb_id,
    })
    if nodes:
        cache["nodes"] = nodes
    if elb_config:
        cache["elb_config"] = elb_config
    if eip_configs:
        cache["eip_configs"] = eip_configs
    save_cache(cache)
    print(f"\n  已缓存到 .lakeon-cloud.json")
    return cache


def cmd_stop_cloud(ak, sk, full=False):
    cache = load_cache()
    if not cache.get("project_id"):
        cache = cmd_discover(ak, sk)
    pid = cache["project_id"]
    cid = cache["cluster_id"]

    # Refresh and save current node specs for recreation
    nodes = get_nodes(ak, sk, pid, cid)
    cache["nodes"] = nodes
    cache["stop_mode"] = "full" if full else "keep-cce"
    save_cache(cache)

    mode_label = "极致省钱（删除一切）" if full else "保留集群 EIP"
    print(f"\n=== 关停华为云资源 [{mode_label}] ===\n")

    # 1. Delete ELB (listeners should be gone after helm uninstall)
    elb_id = cache.get("elb_id")
    if elb_id:
        lb = get_elb(ak, sk, pid, elb_id)
        if lb:
            cache["elb_config"] = {
                "name": lb.get("name", "lakeon-elb"),
                "vip_subnet_id": lb.get("vip_subnet_id") or lb.get("vip_subnet_cidr_id"),
                "shared": True,
            }
            save_cache(cache)

            print(f"  删除 ELB: {elb_id[:12]}...")
            # Use v2 cascade delete to clean listeners/pools automatically
            s, r = api("DELETE", f"https://elb.{REGION}.myhuaweicloud.com/v2/{pid}/elb/loadbalancers/{elb_id}?cascade=true", ak, sk)
            if s not in (200, 204):
                # Fallback: try v3 API
                s, r = delete_elb(ak, sk, pid, elb_id)
            print(f"    {'✓' if s in (200,204) else '✗'} {s}")
        else:
            print(f"  ELB {elb_id[:12]}... 已不存在")

    # 2. Release EIPs (ELB-managed EIPs are auto-deleted with ELB, skip them)
    cluster_eip = _get_cluster_eip()
    if full:
        # Full mode: release ALL standalone EIPs including cluster EIP
        all_eips = get_eips(ak, sk, pid)
        all_eip_configs = []
        for e in all_eips:
            all_eip_configs.append({
                "ip": e.get("public_ip_address"), "id": e["id"],
                "type": e.get("type", "5_bgp"),
                "bandwidth_size": e.get("bandwidth_size", 5),
                "bandwidth_share_type": e.get("bandwidth_share_type", "PER"),
                "bandwidth_name": e.get("bandwidth_name", "lakeon-bw"),
                "is_cluster_eip": e.get("public_ip_address") == cluster_eip,
            })
        cache["eip_configs"] = all_eip_configs
        save_cache(cache)
        for e in all_eip_configs:
            label = " (集群)" if e.get("is_cluster_eip") else ""
            print(f"  释放 EIP: {e['ip']}{label} ({e['id'][:12]}...)")
            s, r = release_eip(ak, sk, pid, e["id"])
            print(f"    {'✓' if s in (200,204) else '✗'} {s}")
    else:
        # Default mode: release standalone service EIPs, keep cluster EIP
        # ELB-managed EIPs are auto-deleted with ELB, no need to release
        for e in cache.get("eip_configs", []):
            if e.get("elb_managed"):
                print(f"  EIP {e['ip']}: 随 ELB 自动释放")
                continue
            print(f"  释放 EIP: {e['ip']} ({e['id'][:12]}...)")
            s, r = release_eip(ak, sk, pid, e["id"])
            print(f"    {'✓' if s in (200,204) else '✗'} {s}")
        if cluster_eip:
            print(f"  保留 EIP: {cluster_eip} (集群管理)")

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

    saved = "¥89" if full else "¥65"
    print(f"\n✅ 关停请求已提交")
    print(f"   每天节省 ~{saved}")
    if not full:
        print(f"   仍计费: CCE 集群 + 集群 EIP ≈ ¥24/天")
    if full:
        print(f"   ⚠ 启动时需先在华为云控制台为 CCE 集群绑定 EIP，再更新 kubeconfig")


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
                _, _, st, _ = get_rds(ak, sk, pid)
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

    # 4. Create shared ELB (v2 API)
    elb_cfg = cache.get("elb_config")
    new_elb_id = None
    new_eip = None
    if elb_cfg:
        print(f"\n  创建共享型 ELB...")
        elb_body = json.dumps({"loadbalancer": {
            "name": elb_cfg.get("name", "lakeon-elb"),
            "vip_subnet_id": elb_cfg.get("vip_subnet_id") or elb_cfg.get("vip_subnet_cidr_id", ""),
            "description": "Lakeon ELB (shared)",
            "admin_state_up": True,
        }})
        s, r = api("POST", f"https://elb.{REGION}.myhuaweicloud.com/v2/{pid}/elb/loadbalancers", ak, sk, elb_body)
        if s in (200, 201):
            lb = r.get("loadbalancer", r)
            new_elb_id = lb["id"]
            vip_port_id = lb.get("vip_port_id")
            print(f"    ✓ ELB: {new_elb_id[:12]}...")
            cache["elb_id"] = new_elb_id

            # Allocate static BGP EIP (traffic billing) and bind to ELB
            print(f"  分配 EIP (静态BGP, 按流量)...")
            eip_req = {
                "publicip": {"type": "5_sbgp"},
                "bandwidth": {"name": "lakeon-bw", "size": 300, "share_type": "PER", "charge_mode": "traffic"},
            }
            s2, r2 = create_eip(ak, sk, pid, eip_req)
            if s2 in (200, 201):
                eip = r2.get("publicip", r2)
                eip_id = eip["id"]
                eip_ip = eip.get("public_ip_address", "?")
                print(f"    ✓ EIP: {eip_ip}")

                print(f"  绑定 EIP → ELB...")
                bs, br = bind_eip(ak, sk, pid, eip_id, vip_port_id)
                if bs == 200:
                    new_eip = br.get("publicip", {}).get("public_ip_address", eip_ip)
                    print(f"    ✓ 已绑定: {new_eip}")
                else:
                    new_eip = eip_ip
                    print(f"    ✗ 绑定失败: {bs}, EIP 需手动绑定")

                cache["eip_configs"] = [{
                    "ip": new_eip, "id": eip_id, "type": "5_sbgp",
                    "bandwidth_size": 300, "bandwidth_share_type": "PER",
                    "charge_mode": "traffic", "bandwidth_name": "lakeon-bw",
                }]
            else:
                print(f"    ✗ EIP 分配失败: {s2} {json.dumps(r2, ensure_ascii=False)[:200]}")
        else:
            print(f"    ✗ ELB 创建失败: {s} {json.dumps(r, ensure_ascii=False)[:300]}")
            return
    else:
        print(f"\n  ⚠ 无 ELB 配置缓存，跳过 ELB 创建")
        return

    save_cache(cache)

    # 5. Update values-cce.yaml
    if new_elb_id and new_eip:
        print(f"\n  更新配置文件...")
        update_values(new_elb_id, new_eip)

    print(f"\n✅ 云资源已就绪")
    if new_eip:
        print(f"\n  新 IP: {new_eip}")
        print(f"  接下来运行 start.sh 完成 K8s 部署")


def get_vpcs(ak, sk, pid):
    s, d = api("GET", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/vpcs?limit=100", ak, sk)
    if s == 200:
        return d.get("vpcs", [])
    return []

def get_subnets(ak, sk, pid):
    s, d = api("GET", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/subnets?limit=100", ak, sk)
    if s == 200:
        return d.get("subnets", [])
    return []

def get_security_groups(ak, sk, pid):
    s, d = api("GET", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/security-groups?limit=100", ak, sk)
    if s == 200:
        return d.get("security_groups", [])
    return []

def get_ecs_servers(ak, sk, pid):
    s, d = api("GET", f"https://ecs.{REGION}.myhuaweicloud.com/v1/{pid}/cloudservers/detail?limit=100", ak, sk)
    if s == 200:
        return d.get("servers", [])
    return []

def get_evs_volumes(ak, sk, pid):
    s, d = api("GET", f"https://evs.{REGION}.myhuaweicloud.com/v2/{pid}/cloudvolumes/detail?limit=100", ak, sk)
    if s == 200:
        return d.get("volumes", [])
    return []

def get_elb_list(ak, sk, pid):
    s, d = api("GET", f"https://elb.{REGION}.myhuaweicloud.com/v3/{pid}/elb/loadbalancers", ak, sk)
    if s == 200:
        return d.get("loadbalancers", [])
    return []


def _console_url(service, resource_type, resource_id, extra=""):
    """Build HWC console URL for a resource."""
    base = f"https://console.huaweicloud.com"
    urls = {
        ("CCE", "集群"): f"{base}/cce2.0/?region={REGION}#/app/cluster/detail?id={resource_id}",
        ("ECS", "云服务器"): f"{base}/ecm/?region={REGION}#/ecs/manager/vmList/vmDetail/overview?instanceId={resource_id}",
        ("RDS", "数据库实例"): f"{base}/rds/?region={REGION}#/rds/management/list/pg/{resource_id}/summary",
        ("ELB", "负载均衡"): f"{base}/elb/?region={REGION}#/elb/detail/{resource_id}",
        ("VPC", "虚拟私有云"): f"{base}/vpc/?region={REGION}#/vpcs/detail/{resource_id}",
        ("VPC", "子网"): f"{base}/vpc/?region={REGION}#/subnets",
        ("VPC", "安全组"): f"{base}/vpc/?region={REGION}#/secGroups/detail/{resource_id}",
        ("VPC", "弹性公网IP"): f"{base}/vpc/?region={REGION}#/eips/detail/{resource_id}",
        ("OBS", "对象存储桶"): f"{base}/obs/?region={REGION}#/obs/manage/{extra}/overview",
        ("EVS", "云硬盘"): f"{base}/evs/?region={REGION}#/evs/manager/volumeDetail/{resource_id}",
    }
    return urls.get((service, resource_type), f"{base}/?region={REGION}")


def cmd_list_resources(ak, sk):
    """Discover all HWC resources and output JSON for the admin console."""
    pid = get_project_id(ak, sk)
    cid, cname = get_cce_cluster(ak, sk, pid)
    nodes = get_nodes(ak, sk, pid, cid)
    rid, rname, rstatus, _ = get_rds(ak, sk, pid)

    # Read ELB ID from values
    with open(VALUES_FILE, encoding='utf-8') as f:
        m = re.search(r'id:\s*"([0-9a-f-]+)"', f.read())
    elb_id = m.group(1) if m else None

    # Read OBS bucket from values
    with open(VALUES_FILE, encoding='utf-8') as f:
        content = f.read()
        bm = re.search(r'bucket:\s*(\S+)', content)
    obs_bucket = bm.group(1) if bm else "lakeon-storage"

    eips = get_eips(ak, sk, pid)
    vpcs = get_vpcs(ak, sk, pid)
    subnets = get_subnets(ak, sk, pid)
    sgs = get_security_groups(ak, sk, pid)
    ecs_servers = get_ecs_servers(ak, sk, pid)
    evs_volumes = get_evs_volumes(ak, sk, pid)
    elbs = get_elb_list(ak, sk, pid)

    resources = []

    # CCE cluster
    resources.append({
        "name": cname, "id": cid, "region": REGION, "region_name": "华北-北京四",
        "service": "CCE", "resource_type": "集群", "status": "Active",
        "console_url": _console_url("CCE", "集群", cid),
    })

    # CCE nodes / ECS
    for n in nodes:
        resources.append({
            "name": n["name"], "id": n["uid"], "region": REGION, "region_name": "华北-北京四",
            "service": "ECS", "resource_type": "云服务器",
            "status": n["phase"],
            "spec": f"{n['flavor']}",
            "console_url": _console_url("ECS", "云服务器", n.get("server_id", n["uid"])),
        })

    # RDS
    resources.append({
        "name": rname, "id": rid, "region": REGION, "region_name": "华北-北京四",
        "service": "RDS", "resource_type": "数据库实例", "status": rstatus,
        "console_url": _console_url("RDS", "数据库实例", rid),
    })

    # ELB
    for lb in elbs:
        resources.append({
            "name": lb.get("name", ""), "id": lb["id"], "region": REGION, "region_name": "华北-北京四",
            "service": "ELB", "resource_type": "负载均衡",
            "status": lb.get("operating_status", "ONLINE"),
            "console_url": _console_url("ELB", "负载均衡", lb["id"]),
        })

    # EIPs
    cluster_eip = _get_cluster_eip()
    for e in eips:
        label = e.get("public_ip_address", "")
        if label == cluster_eip:
            label += " (集群管理)"
        resources.append({
            "name": label, "id": e["id"], "region": REGION, "region_name": "华北-北京四",
            "service": "VPC", "resource_type": "弹性公网IP",
            "status": e.get("status", ""),
            "console_url": _console_url("VPC", "弹性公网IP", e["id"]),
        })

    # VPCs (only lakeon-related)
    for v in vpcs:
        if "lakeon" in v.get("name", "").lower():
            resources.append({
                "name": v.get("name", ""), "id": v["id"], "region": REGION, "region_name": "华北-北京四",
                "service": "VPC", "resource_type": "虚拟私有云",
                "status": v.get("status", "ACTIVE"),
                "console_url": _console_url("VPC", "虚拟私有云", v["id"]),
            })

    # Subnets (only lakeon-related)
    for s in subnets:
        if "lakeon" in s.get("name", "").lower():
            resources.append({
                "name": s.get("name", ""), "id": s["id"], "region": REGION, "region_name": "华北-北京四",
                "service": "VPC", "resource_type": "子网",
                "status": s.get("status", "ACTIVE"),
                "console_url": _console_url("VPC", "子网", s["id"]),
            })

    # Security groups (only lakeon-related)
    for sg in sgs:
        if "lakeon" in sg.get("name", "").lower():
            resources.append({
                "name": sg.get("name", ""), "id": sg["id"], "region": REGION, "region_name": "华北-北京四",
                "service": "VPC", "resource_type": "安全组", "status": "Active",
                "console_url": _console_url("VPC", "安全组", sg["id"]),
            })

    # OBS bucket
    resources.append({
        "name": obs_bucket, "id": obs_bucket, "region": REGION, "region_name": "华北-北京四",
        "service": "OBS", "resource_type": "对象存储桶", "status": "Active",
        "console_url": _console_url("OBS", "对象存储桶", obs_bucket, obs_bucket),
    })

    # EVS volumes
    for vol in evs_volumes:
        resources.append({
            "name": vol.get("name", ""), "id": vol["id"], "region": REGION, "region_name": "华北-北京四",
            "service": "EVS", "resource_type": "云硬盘",
            "status": vol.get("status", ""),
            "console_url": _console_url("EVS", "云硬盘", vol["id"]),
        })

    # Build topology for diagram
    elb_info = None
    if elbs:
        lb = elbs[0]
        elb_info = {"name": lb.get("name", "lakeon-elb"), "id": lb["id"],
                     "console_url": _console_url("ELB", "负载均衡", lb["id"])}

    eip_info = None
    for e in eips:
        if e.get("public_ip_address") != cluster_eip and e.get("status") == "ACTIVE":
            eip_info = {"ip": e["public_ip_address"], "id": e["id"],
                        "console_url": _console_url("VPC", "弹性公网IP", e["id"])}
            break
    if not eip_info and eips:
        e = eips[0]
        eip_info = {"ip": e.get("public_ip_address", ""), "id": e["id"],
                    "console_url": _console_url("VPC", "弹性公网IP", e["id"])}

    topology = {
        "eip": eip_info,
        "elb": elb_info,
        "cce": {
            "name": cname, "id": cid,
            "console_url": _console_url("CCE", "集群", cid),
            "nodes": [{"name": n["name"], "flavor": n["flavor"], "phase": n["phase"],
                        "server_id": n.get("server_id", ""),
                        "console_url": _console_url("ECS", "云服务器", n.get("server_id", n["uid"]))}
                       for n in nodes],
        },
        "rds": {"name": rname, "id": rid, "status": rstatus,
                "console_url": _console_url("RDS", "数据库实例", rid)},
        "obs": {"bucket": obs_bucket,
                "console_url": _console_url("OBS", "对象存储桶", obs_bucket, obs_bucket)},
    }

    output = {"resources": resources, "topology": topology}
    print(json.dumps(output, indent=2, ensure_ascii=False))
    return output


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
    _, rname, rst, _ = get_rds(ak, sk, pid)
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
    "list-resources": cmd_list_resources,
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
    full = "--full" in sys.argv
    cmd = sys.argv[1]
    if cmd == "stop-cloud":
        COMMANDS[cmd](ak, sk, full=full)
    else:
        COMMANDS[cmd](ak, sk)
