#!/usr/bin/env python3
"""Provision the DBay control-plane CCE cluster.

Default mode is plan-only. Pass --execute to create the cluster and node pool.
"""

import argparse
import base64
import importlib.util
import json
import os
import secrets
import string
import sys
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)

import hwcloud  # noqa: E402
from passlib.hash import sha512_crypt  # noqa: E402


CONTROL_CLUSTER_NAME = "dbay-control-cce"
CONTROL_NODEPOOL_NAME = "dbay-control-pool"
SOURCE_CLUSTER_NAME = "dbay-cce"
PASSWORD_SPECIALS = "!@$%^-_=+[{}]:,./?"
MASTER_PORT = 5443


def existing_cluster(ak, sk, project_id, name):
    status, data = hwcloud.api(
        "GET",
        f"https://cce.{hwcloud.REGION}.myhuaweicloud.com/api/v3/projects/{project_id}/clusters",
        ak,
        sk,
    )
    if status != 200:
        raise RuntimeError(f"list clusters failed: {status} {data}")
    for item in data.get("items", []):
        if item.get("metadata", {}).get("name") == name:
            return item
    return None


def load_acl_module():
    path = os.path.join(SCRIPT_DIR, "update-cce-acl.py")
    spec = importlib.util.spec_from_file_location("update_cce_acl", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sync_master_acl(ak, sk, project_id, security_group_id, dry_run=False):
    acl = load_acl_module()
    real_ip = acl.get_real_outbound_ip()
    target_cidr = f"{real_ip}/32"
    existing = acl.list_5443_ingress(ak, sk, project_id, security_group_id)

    print(f"control-plane master SG: {security_group_id}")
    print(f"allowed MacBook CIDR: {target_cidr}")

    if not any(rule.get("remote_ip_prefix") == target_cidr for rule in existing):
        acl.add_rule(ak, sk, project_id, security_group_id, target_cidr, dry_run)

    for rule in existing:
        cidr = rule.get("remote_ip_prefix")
        if not cidr:
            continue
        if cidr in acl.KEEP_CIDRS or cidr == target_cidr:
            continue
        acl.del_rule(ak, sk, project_id, rule["id"], cidr, dry_run)


def ensure_master_eip(ak, sk, project_id, cluster_id):
    status, cluster = hwcloud.api(
        "GET",
        f"https://cce.{hwcloud.REGION}.myhuaweicloud.com/api/v3/projects/{project_id}/clusters/{cluster_id}",
        ak,
        sk,
    )
    if status != 200:
        raise RuntimeError(f"get cluster before EIP bind failed: {status} {cluster}")
    for endpoint in cluster.get("status", {}).get("endpoints", []):
        if endpoint.get("type") == "External":
            print(f"master EIP already bound: {endpoint.get('url')}")
            return endpoint.get("url")

    eip_req = {
        "publicip": {"type": "5_sbgp"},
        "bandwidth": {
            "name": "dbay-control-cce-api-bw",
            "size": 5,
            "share_type": "PER",
            "charge_mode": "traffic",
        },
    }
    status, data = hwcloud.create_eip(ak, sk, project_id, eip_req)
    if status not in (200, 201):
        raise RuntimeError(f"create master EIP failed: {status} {data}")
    eip = data.get("publicip", data)
    eip_id = eip["id"]
    eip_ip = eip.get("public_ip_address")

    body = json.dumps({"spec": {"action": "bind", "spec": {"id": eip_id}}})
    status, data = hwcloud.api(
        "PUT",
        f"https://cce.{hwcloud.REGION}.myhuaweicloud.com/api/v3/projects/{project_id}/clusters/{cluster_id}/mastereip",
        ak,
        sk,
        body,
        timeout=60,
    )
    if status not in (200, 201, 202):
        raise RuntimeError(f"bind master EIP failed: {status} {data}")
    endpoint = data.get("status", {}).get("publicEndpoint") or f"https://{eip_ip}:{MASTER_PORT}"
    print(f"master EIP bound: {endpoint}")
    return endpoint


def wait_cluster_available(ak, sk, project_id, cluster_id, timeout_seconds=1800):
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        status, data = hwcloud.api(
            "GET",
            f"https://cce.{hwcloud.REGION}.myhuaweicloud.com/api/v3/projects/{project_id}/clusters/{cluster_id}",
            ak,
            sk,
        )
        if status == 200:
            phase = data.get("status", {}).get("phase")
            print(f"cluster phase: {phase}")
            if phase == "Available":
                return data
            if phase in {"Error", "Unavailable"}:
                raise RuntimeError(f"cluster entered {phase}: {data}")
        else:
            print(f"cluster status query failed: {status} {data}")
        time.sleep(20)
    raise TimeoutError(f"cluster {cluster_id} was not Available within {timeout_seconds}s")


def build_cluster_body(source_cluster):
    spec = source_cluster["spec"]
    host = spec["hostNetwork"]
    eni = spec["eniNetwork"]
    return {
        "kind": "Cluster",
        "apiVersion": "v3",
        "metadata": {
            "name": CONTROL_CLUSTER_NAME,
            "alias": CONTROL_CLUSTER_NAME,
        },
        "spec": {
            "category": spec.get("category", "Turbo"),
            "type": spec.get("type", "VirtualMachine"),
            "flavor": spec.get("flavor", "cce.s1.small"),
            "version": spec.get("version", "v1.33"),
            "description": "DBay control-plane CCE; data-plane remains in dbay-cce",
            "ipv6enable": False,
            "billingMode": 0,
            "hostNetwork": {
                "vpc": host["vpc"],
                "subnet": host["subnet"],
                "SecurityGroup": host.get("SecurityGroup"),
            },
            "containerNetwork": {
                "mode": spec.get("containerNetwork", {}).get("mode", "eni"),
            },
            "eniNetwork": {
                "eniSubnetId": eni["eniSubnetId"],
                "subnets": eni.get("subnets", [{"subnetID": eni["eniSubnetId"]}]),
            },
            "authentication": {
                "mode": "rbac",
            },
        },
    }


def build_nodepool_body(source_node, initial_count, max_count, password):
    salted_password = sha512_crypt.using(rounds=5000).hash(password)
    encoded_password = base64.b64encode(salted_password.encode()).decode()
    data_volumes = [
        {**volume, "size": max(int(volume.get("size", 100)), 100)}
        for volume in source_node["data_volumes"]
    ]
    return {
        "kind": "NodePool",
        "apiVersion": "v3",
        "metadata": {
            "name": CONTROL_NODEPOOL_NAME,
        },
        "spec": {
            "initialNodeCount": initial_count,
            "type": "vm",
            "autoscaling": {
                "enable": True,
                "minNodeCount": initial_count,
                "maxNodeCount": max_count,
                "scaleDownCooldownTime": 10,
                "priority": 0,
                "scaleDownUnneededTime": 10,
                "scaleDownUtilizationThreshold": 0.5,
            },
            "nodeManagement": {
                "serverGroupReference": "",
            },
            "nodeTemplate": {
                "flavor": source_node["flavor"],
                "az": source_node["az"],
                "os": source_node["os"],
                "login": {
                    "userPassword": {
                        "password": encoded_password,
                    },
                },
                "rootVolume": source_node["root_volume"],
                "dataVolumes": data_volumes,
                "billingMode": 0,
                "runtime": source_node.get("runtime", {"name": "containerd"}),
                "extendParam": {
                    "maxPods": 110,
                },
                "nodeNicSpec": {
                    "primaryNic": {
                        "subnetId": source_node["subnet_id"],
                    },
                },
                "k8sTags": {
                    "lakeon/pool": "control",
                },
            },
        },
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true", help="create resources")
    parser.add_argument("--bind-master-eip", action="store_true", help="bind a public master EIP after syncing the /32 ACL")
    parser.add_argument("--initial-nodes", type=int, default=2)
    parser.add_argument("--max-nodes", type=int, default=3)
    args = parser.parse_args()

    ak, sk, creds = hwcloud.load_credentials()
    if not ak or not sk:
        raise SystemExit("HWCLOUD_AK/HWCLOUD_SK are required")
    node_password = (
        os.environ.get("CCE_CONTROL_NODE_PASSWORD")
        or os.environ.get("CCE_NODE_PASSWORD")
        or creds.get("CCE_CONTROL_NODE_PASSWORD")
        or creds.get("CCE_NODE_PASSWORD")
    )
    if not node_password:
        node_password = generate_node_password()
        print("CCE_CONTROL_NODE_PASSWORD/CCE_NODE_PASSWORD not set; generated a compliant password for this run.")
    elif not is_valid_node_password(node_password):
        node_password = generate_node_password()
        print("Configured node password does not match CCE password rules; generated a compliant password for this run.")

    project_id = hwcloud.get_project_id(ak, sk)
    source = existing_cluster(ak, sk, project_id, SOURCE_CLUSTER_NAME)
    if not source:
        raise RuntimeError(f"source data-plane cluster not found: {SOURCE_CLUSTER_NAME}")
    source_cluster_id = source["metadata"]["uid"]
    status, source_cluster = hwcloud.api(
        "GET",
        f"https://cce.{hwcloud.REGION}.myhuaweicloud.com/api/v3/projects/{project_id}/clusters/{source_cluster_id}",
        ak,
        sk,
    )
    if status != 200:
        raise RuntimeError(f"get source cluster failed: {status} {source_cluster}")

    source_nodes = [
        node for node in hwcloud.get_nodes(ak, sk, project_id, source_cluster_id)
        if node["flavor"].startswith(("c9.", "c7.", "s6.", "c6."))
    ]
    if not source_nodes:
        raise RuntimeError("no general-purpose source node found to copy")
    source_node = source_nodes[0]

    cluster_body = build_cluster_body(source_cluster)
    nodepool_body = build_nodepool_body(source_node, args.initial_nodes, args.max_nodes, node_password)

    print("Control-plane CCE plan:")
    print(json.dumps({
        "cluster": cluster_body,
        "nodepool": {
            **nodepool_body,
            "spec": {
                **nodepool_body["spec"],
                "nodeTemplate": {
                    **nodepool_body["spec"]["nodeTemplate"],
                    "login": {"userPassword": {"password": "***"}},
                },
            },
        },
    }, ensure_ascii=False, indent=2))

    if not args.execute:
        print("Plan only. Re-run with --execute to create the cluster and node pool.")
        return

    cluster = existing_cluster(ak, sk, project_id, CONTROL_CLUSTER_NAME)
    if cluster:
        cluster_id = cluster["metadata"]["uid"]
        print(f"cluster already exists: {CONTROL_CLUSTER_NAME} {cluster_id}")
    else:
        status, data = hwcloud.api(
            "POST",
            f"https://cce.{hwcloud.REGION}.myhuaweicloud.com/api/v3/projects/{project_id}/clusters",
            ak,
            sk,
            json.dumps(cluster_body),
            timeout=60,
        )
        if status not in (200, 201, 202):
            raise RuntimeError(f"create cluster failed: {status} {data}")
        cluster_id = data.get("metadata", {}).get("uid") or data.get("uid")
        if not cluster_id:
            raise RuntimeError(f"create cluster response missing uid: {data}")
        print(f"cluster create submitted: {cluster_id}")

    wait_cluster_available(ak, sk, project_id, cluster_id)

    status, current_cluster = hwcloud.api(
        "GET",
        f"https://cce.{hwcloud.REGION}.myhuaweicloud.com/api/v3/projects/{project_id}/clusters/{cluster_id}",
        ak,
        sk,
    )
    if status != 200:
        raise RuntimeError(f"get current cluster failed: {status} {current_cluster}")
    control_sg = current_cluster["spec"]["hostNetwork"]["controlPlaneSecurityGroup"]
    sync_master_acl(ak, sk, project_id, control_sg, dry_run=False)

    status, nodepools = hwcloud.api(
        "GET",
        f"https://cce.{hwcloud.REGION}.myhuaweicloud.com/api/v3/projects/{project_id}/clusters/{cluster_id}/nodepools",
        ak,
        sk,
    )
    nodepool_exists = status == 200 and any(
        np.get("metadata", {}).get("name") == CONTROL_NODEPOOL_NAME
        for np in nodepools.get("items", [])
    )
    if nodepool_exists:
        print(f"node pool already exists: {CONTROL_NODEPOOL_NAME}")
    else:
        status, data = hwcloud.api(
            "POST",
            f"https://cce.{hwcloud.REGION}.myhuaweicloud.com/api/v3/projects/{project_id}/clusters/{cluster_id}/nodepools",
            ak,
            sk,
            json.dumps(nodepool_body),
            timeout=60,
        )
        if status not in (200, 201, 202):
            raise RuntimeError(f"create node pool failed: {status} {data}")
        print(f"node pool create submitted: {data.get('metadata', {}).get('uid', '')}")

    if args.bind_master_eip:
        ensure_master_eip(ak, sk, project_id, cluster_id)


def is_valid_node_password(password):
    if not (8 <= len(password) <= 26):
        return False
    classes = [
        any(ch.isupper() for ch in password),
        any(ch.islower() for ch in password),
        any(ch.isdigit() for ch in password),
        any(ch in PASSWORD_SPECIALS for ch in password),
    ]
    allowed = set(string.ascii_letters + string.digits + PASSWORD_SPECIALS)
    return sum(classes) >= 3 and all(ch in allowed for ch in password)


def generate_node_password():
    alphabet = string.ascii_letters + string.digits + PASSWORD_SPECIALS
    while True:
        password = (
            secrets.choice(string.ascii_uppercase)
            + secrets.choice(string.ascii_lowercase)
            + secrets.choice(string.digits)
            + secrets.choice(PASSWORD_SPECIALS)
            + "".join(secrets.choice(alphabet) for _ in range(12))
        )
        password = "".join(secrets.SystemRandom().sample(password, len(password)))
        if is_valid_node_password(password):
            return password


if __name__ == "__main__":
    main()
