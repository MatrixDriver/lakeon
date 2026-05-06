#!/usr/bin/env python3
"""同步 CCE master SG 5443 入向白名单到当前开发机出网 IP。

幂等：可在每次 deploy 前无脑跑一遍，IP 没变就什么都不做。
自动发现：通过 master EIP → port → security_groups 找到 master SG，不依赖硬编码。

用法：
    python3 deploy/cce/update-cce-acl.py            # 当前站点 (默认 hwstaff)
    SITE=jackylk python3 deploy/cce/update-cce-acl.py

约定：
    - "真实 IP" 一律走直连（NO_PROXY），因为 CCE 在国内，本地 GFW 代理出口 IP
      （如 45.x、104.x）跟 CCE 看到的源 IP 不同，加了等于没加。
    - 保留 VPC 内网 CIDR (192.168.0.0/16) 和同 SG 自引用规则。
    - 其他 5443 ingress CIDR 视为过期 dev IP，删掉。
      多人共用 CCE 时，把要保留的 IP 写入 KEEP_CIDRS 即可。
"""
import os
import sys
import json
import urllib.request
import urllib.error
from typing import Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from hwcloud import (
    load_credentials, api, get_project_id, _get_cluster_eip, REGION
)

# 显式保留的 CIDR — 不会被当作"过期 dev IP"删除
KEEP_CIDRS = {
    "192.168.0.0/24",   # CCE VPC 内部
    "192.168.0.0/16",   # 兜底 VPC 兼容
}

PORT = 5443
PROTO = "tcp"


def get_real_outbound_ip() -> str:
    """直连（绕过代理）拿到当前真实出网 IP — CCE 看到的源 IP。"""
    # 强制空代理 env，让 urllib 走直连
    env = os.environ.copy()
    for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"):
        env.pop(k, None)

    proxy_handler = urllib.request.ProxyHandler({})
    opener = urllib.request.build_opener(proxy_handler)
    last_err = None
    for url in ("https://ifconfig.me", "https://ipv4.icanhazip.com", "https://api.myip.com"):
        try:
            with opener.open(url, timeout=5) as resp:
                raw = resp.read().decode().strip()
                if url.endswith("myip.com"):
                    raw = json.loads(raw).get("ip", "")
                # very rough sanity check — IPv4 dotted quad
                parts = raw.split(".")
                if len(parts) == 4 and all(p.isdigit() for p in parts):
                    return raw
        except Exception as e:
            last_err = e
            continue
    raise RuntimeError(f"无法拿到出网 IP（已绕过代理）: {last_err}")


def find_master_sg(ak: str, sk: str, pid: str, master_eip: str) -> str:
    """通过 master EIP 找到挂载它的 SG（应该有且仅有一个含 5443 ingress）。"""
    s, d = api("GET", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/publicips?limit=200", ak, sk)
    if s != 200:
        raise RuntimeError(f"列 EIP 失败: HTTP {s} {d}")
    eip = next((e for e in d.get("publicips", []) if e.get("public_ip_address") == master_eip), None)
    if not eip:
        raise RuntimeError(f"没找到 EIP {master_eip}")
    port_id = eip.get("port_id")
    if not port_id:
        raise RuntimeError(f"EIP {master_eip} 没绑定到 port — 集群可能挂了")

    s, d = api("GET", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/ports/{port_id}", ak, sk)
    if s != 200:
        raise RuntimeError(f"查 port {port_id} 失败: HTTP {s} {d}")
    sgs = d.get("port", {}).get("security_groups", []) or []
    if not sgs:
        raise RuntimeError(f"port {port_id} 没有任何 SG，无法继续")

    # 在挂载的 SG 里找有 5443 ingress 规则的那一个 = master SG
    for sg_id in sgs:
        s, d = api("GET", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/security-groups/{sg_id}", ak, sk)
        if s != 200:
            continue
        for r in d.get("security_group", {}).get("security_group_rules", []):
            if (
                r.get("direction") == "ingress"
                and r.get("port_range_min") == PORT
                and r.get("port_range_max") == PORT
                and r.get("protocol") == PROTO
            ):
                return sg_id
    # fallback: 第一个挂载的 SG（CCE 通常只挂一个 control SG）
    return sgs[0]


def list_5443_ingress(ak: str, sk: str, pid: str, sg_id: str):
    s, d = api("GET", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/security-groups/{sg_id}", ak, sk)
    if s != 200:
        raise RuntimeError(f"查 SG {sg_id} 失败: HTTP {s} {d}")
    rules = []
    for r in d.get("security_group", {}).get("security_group_rules", []):
        if (
            r.get("direction") == "ingress"
            and r.get("port_range_min") == PORT
            and r.get("port_range_max") == PORT
            and r.get("protocol") == PROTO
        ):
            rules.append(r)
    return rules


def add_rule(ak: str, sk: str, pid: str, sg_id: str, cidr: str, dry: bool):
    if dry:
        print(f"  [dry-run] 会新增 {cidr}:{PORT}/{PROTO}")
        return
    body = json.dumps({"security_group_rule": {
        "security_group_id": sg_id,
        "direction": "ingress",
        "ethertype": "IPv4",
        "protocol": PROTO,
        "port_range_min": PORT,
        "port_range_max": PORT,
        "remote_ip_prefix": cidr,
        "description": "kube-apiserver public access — synced by update-cce-acl.py",
    }})
    s, d = api("POST", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/security-group-rules", ak, sk, body=body)
    if s not in (200, 201):
        raise RuntimeError(f"新增规则失败 ({cidr}): HTTP {s} {d}")


def del_rule(ak: str, sk: str, pid: str, rule_id: str, cidr: str, dry: bool):
    if dry:
        print(f"  [dry-run] 会删除 {cidr} (id={rule_id[:8]})")
        return
    s, d = api("DELETE", f"https://vpc.{REGION}.myhuaweicloud.com/v1/{pid}/security-group-rules/{rule_id}", ak, sk)
    if s not in (200, 204):
        raise RuntimeError(f"删除规则失败 ({cidr}): HTTP {s} {d}")


def main() -> int:
    dry = "--dry-run" in sys.argv

    print("── CCE Master SG 5443 白名单同步 ──")

    # 1. 真实出网 IP
    real_ip = get_real_outbound_ip()
    target_cidr = f"{real_ip}/32"
    print(f"  当前真实出网 IP: {real_ip}")

    # 2. 凭据 + 集群 EIP
    ak, sk, _ = load_credentials()
    if not ak or not sk:
        print("  ❌ 没拿到 HWCLOUD_AK/SK（检查 sites/<SITE>/.env）", file=sys.stderr)
        return 1
    master_eip = _get_cluster_eip()
    if not master_eip:
        print("  ❌ kubeconfig 里没解析出 master EIP", file=sys.stderr)
        return 1
    pid = get_project_id(ak, sk)
    print(f"  Master EIP: {master_eip}")

    # 3. 找 SG
    sg_id = find_master_sg(ak, sk, pid, master_eip)
    print(f"  Master SG: {sg_id}")

    # 4. 读现有规则
    existing = list_5443_ingress(ak, sk, pid, sg_id)
    print(f"\n  当前 5443/{PROTO} ingress: {len(existing)} 条")
    for r in existing:
        cidr = r.get("remote_ip_prefix") or f"sg:{r.get('remote_group_id', '')[:8]}"
        print(f"    - {cidr} (id={r.get('id')[:8]})")

    # 5. 决策：要加什么、删什么
    have_target = any(r.get("remote_ip_prefix") == target_cidr for r in existing)
    to_delete = []
    for r in existing:
        cidr = r.get("remote_ip_prefix")
        if not cidr:
            continue  # self-SG self-reference — 保留
        if cidr in KEEP_CIDRS:
            continue
        if cidr == target_cidr:
            continue
        to_delete.append(r)

    if have_target and not to_delete:
        print(f"\n  ✅ 白名单已是最新（包含 {target_cidr}，无过期项）")
        return 0

    # 6. 应用变更：先加再删，避免短暂全空窗口
    if not have_target:
        print(f"\n  + 新增 {target_cidr}")
        add_rule(ak, sk, pid, sg_id, target_cidr, dry)
    if to_delete:
        print(f"  - 删除 {len(to_delete)} 条过期规则")
        for r in to_delete:
            del_rule(ak, sk, pid, r["id"], r.get("remote_ip_prefix", "?"), dry)

    print("\n  ✅ 同步完成" if not dry else "\n  ✅ dry-run 完毕，未实际改动")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
    except Exception as e:
        print(f"\n❌ {e}", file=sys.stderr)
        sys.exit(1)
