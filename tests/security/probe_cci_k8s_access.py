"""
Security probe: test CCI pod isolation from K8s API, cloud metadata, and network.
Submit this as a datalake Python job to verify CCI security hardening.

Expected results after hardening:
  - sa_token_mounted: false
  - K8s API: unreachable or no credentials
  - metadata API: blocked
  - uid: 1000 (non-root)
"""
import os
import json
import socket

results = {}
issues = []

# ═══════════════════════════════════════════════════════════
# 1. K8s Service Account Token
# ═══════════════════════════════════════════════════════════
token_path = "/var/run/secrets/kubernetes.io/serviceaccount/token"
results["sa_token_mounted"] = os.path.exists(token_path)
if results["sa_token_mounted"]:
    results["sa_token_length"] = len(open(token_path).read())
    ca_path = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
    results["ca_cert_mounted"] = os.path.exists(ca_path)
    issues.append("SA token is mounted — K8s API credentials available")

# ═══════════════════════════════════════════════════════════
# 2. K8s API Server reachability
# ═══════════════════════════════════════════════════════════
results["KUBERNETES_SERVICE_HOST"] = os.environ.get("KUBERNETES_SERVICE_HOST", "NOT SET")
results["KUBERNETES_SERVICE_PORT"] = os.environ.get("KUBERNETES_SERVICE_PORT", "NOT SET")

k8s_host = os.environ.get("KUBERNETES_SERVICE_HOST", "")
k8s_targets = [("kubernetes.default.svc", 443)]
if k8s_host:
    k8s_targets.insert(0, (k8s_host, int(os.environ.get("KUBERNETES_SERVICE_PORT", "443"))))
for common_ip in ["10.247.0.1", "10.96.0.1"]:
    if common_ip != k8s_host:
        k8s_targets.append((common_ip, 443))

for host, port in k8s_targets:
    key = f"k8s_tcp_{host}:{port}"
    try:
        s = socket.create_connection((host, port), timeout=3)
        s.close()
        results[key] = "REACHABLE"
        issues.append(f"K8s API Server reachable: {host}:{port}")
    except socket.timeout:
        results[key] = "TIMEOUT (blocked or no route)"
    except socket.gaierror:
        results[key] = "DNS_FAILED (no cluster DNS)"
    except OSError as e:
        results[key] = f"BLOCKED: {e}"

# If K8s API is reachable, try HTTP
if k8s_host:
    try:
        import urllib.request
        import ssl
        ctx = ssl.create_unverified_context()
        url = f"https://{k8s_host}:{os.environ.get('KUBERNETES_SERVICE_PORT', '443')}/version"
        req = urllib.request.Request(url, headers={"Authorization": "Bearer anonymous"})
        resp = urllib.request.urlopen(req, timeout=5, context=ctx)
        results["k8s_api_http_version"] = f"ACCESSIBLE status={resp.status}"
        issues.append("K8s API HTTP accessible (anonymous /version)")
    except Exception as e:
        results["k8s_api_http_version"] = f"BLOCKED: {type(e).__name__}"

    # Try with SA token if mounted
    if results["sa_token_mounted"]:
        try:
            token = open(token_path).read().strip()
            url = f"https://{k8s_host}:{os.environ.get('KUBERNETES_SERVICE_PORT', '443')}/api/v1/namespaces"
            req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
            resp = urllib.request.urlopen(req, timeout=5, context=ctx)
            results["k8s_api_list_namespaces"] = f"ACCESSIBLE status={resp.status}"
            issues.append("K8s API: can list namespaces with SA token!")
        except Exception as e:
            results["k8s_api_list_namespaces"] = f"DENIED: {type(e).__name__}"

# ═══════════════════════════════════════════════════════════
# 3. Cloud Metadata API (CRITICAL — can leak IAM credentials)
# ═══════════════════════════════════════════════════════════
metadata_endpoints = [
    # Huawei Cloud metadata
    ("169.254.169.254", 80, "http://169.254.169.254/openstack/latest/meta_data.json", "hwcloud_metadata"),
    ("169.254.169.254", 80, "http://169.254.169.254/openstack/latest/securitykey", "hwcloud_iam_credentials"),
    # AWS-style metadata (some CCI implementations)
    ("169.254.169.254", 80, "http://169.254.169.254/latest/meta-data/", "aws_style_metadata"),
    # Azure-style metadata
    ("169.254.169.254", 80, "http://169.254.169.254/metadata/instance?api-version=2021-02-01", "azure_style_metadata"),
]

# First check TCP reachability
try:
    s = socket.create_connection(("169.254.169.254", 80), timeout=3)
    s.close()
    results["metadata_tcp_169.254.169.254:80"] = "REACHABLE"
    issues.append("Cloud metadata endpoint is TCP reachable (169.254.169.254)")

    # TCP is reachable, try each HTTP endpoint
    import urllib.request
    for _, _, url, label in metadata_endpoints:
        try:
            req = urllib.request.Request(url)
            if "azure" in label:
                req.add_header("Metadata", "true")
            resp = urllib.request.urlopen(req, timeout=5)
            body = resp.read(2048).decode("utf-8", errors="replace")
            results[f"metadata_{label}"] = f"ACCESSIBLE (len={len(body)})"
            results[f"metadata_{label}_preview"] = body[:500]
            issues.append(f"Cloud metadata ACCESSIBLE: {label}")
        except Exception as e:
            results[f"metadata_{label}"] = f"BLOCKED: {type(e).__name__}"

except socket.timeout:
    results["metadata_tcp_169.254.169.254:80"] = "TIMEOUT (blocked)"
except OSError as e:
    results["metadata_tcp_169.254.169.254:80"] = f"BLOCKED: {e}"

# ═══════════════════════════════════════════════════════════
# 4. Container identity: UID/GID
# ═══════════════════════════════════════════════════════════
results["uid"] = os.getuid()
results["gid"] = os.getgid()
results["euid"] = os.geteuid()
results["is_root"] = os.getuid() == 0
if results["is_root"]:
    issues.append("Running as root (uid=0)")

# ═══════════════════════════════════════════════════════════
# 5. Filesystem writability
# ═══════════════════════════════════════════════════════════
for path in ["/", "/etc", "/tmp", "/app", "/proc/sysrq-trigger"]:
    try:
        test_file = os.path.join(path, ".security_probe_test")
        with open(test_file, "w") as f:
            f.write("test")
        os.remove(test_file)
        results[f"writable_{path}"] = True
    except Exception:
        results[f"writable_{path}"] = False

# ═══════════════════════════════════════════════════════════
# 6. Internet / public network access
# ═══════════════════════════════════════════════════════════
internet_targets = [
    ("8.8.8.8", 53, "Google DNS"),
    ("114.114.114.114", 53, "China DNS"),
    ("100.125.1.250", 443, "HuaweiCloud OBS internal"),
]
for host, port, label in internet_targets:
    key = f"net_{label.replace(' ', '_')}_{host}:{port}"
    try:
        s = socket.create_connection((host, port), timeout=3)
        s.close()
        results[key] = "REACHABLE"
    except Exception as e:
        results[key] = f"BLOCKED: {e}"

# ═══════════════════════════════════════════════════════════
# 7. Linux capabilities & kernel info
# ═══════════════════════════════════════════════════════════
try:
    with open("/proc/self/status") as f:
        for line in f:
            if line.startswith("Cap"):
                k, v = line.strip().split(":\t", 1)
                results[f"proc_{k}"] = v
except Exception:
    pass

# ═══════════════════════════════════════════════════════════
# 8. Environment variables (check for leaked secrets)
# ═══════════════════════════════════════════════════════════
sensitive_env_keys = [k for k in os.environ if any(
    s in k.upper() for s in ["SECRET", "PASSWORD", "TOKEN", "KEY", "CREDENTIAL"]
)]
results["sensitive_env_count"] = len(sensitive_env_keys)
results["sensitive_env_keys"] = sensitive_env_keys

# ═══════════════════════════════════════════════════════════
# Report
# ═══════════════════════════════════════════════════════════
print("=" * 60)
print("CCI Pod Security Probe Results")
print("=" * 60)
print(json.dumps(results, indent=2, ensure_ascii=False))

print("\n" + "=" * 60)
if issues:
    severity = "CRITICAL" if any("metadata" in i.lower() and "ACCESSIBLE" in i for i in issues) else \
               "HIGH" if any("SA token" in i or "K8s API" in i for i in issues) else "MEDIUM"
    print(f"⚠️  {severity}: {len(issues)} SECURITY ISSUE(S) FOUND:")
    for i in issues:
        print(f"  - {i}")
else:
    print("✅ All checks passed — CCI pod is properly isolated")
print("=" * 60)
