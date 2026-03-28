"""
Security probe: test whether a CCI pod can access K8s API Server.
Submit this as a datalake Python job to verify CCI isolation.

Expected result after hardening:
  - sa_token_mounted: false
  - KUBERNETES_SERVICE_HOST: NOT SET (or set but unreachable)
  - All TCP connections: BLOCKED
  - uid: 1000 (non-root)
"""
import os
import json
import socket

results = {}

# 1. Check if SA token is mounted
token_path = "/var/run/secrets/kubernetes.io/serviceaccount/token"
results["sa_token_mounted"] = os.path.exists(token_path)
if results["sa_token_mounted"]:
    results["sa_token_length"] = len(open(token_path).read())
    ca_path = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
    results["ca_cert_mounted"] = os.path.exists(ca_path)

# 2. Check K8s service env vars
results["KUBERNETES_SERVICE_HOST"] = os.environ.get("KUBERNETES_SERVICE_HOST", "NOT SET")
results["KUBERNETES_SERVICE_PORT"] = os.environ.get("KUBERNETES_SERVICE_PORT", "NOT SET")

# 3. Try TCP connection to K8s API server
k8s_host = os.environ.get("KUBERNETES_SERVICE_HOST", "")
targets = [("kubernetes.default.svc", 443)]
if k8s_host:
    targets.insert(0, (k8s_host, int(os.environ.get("KUBERNETES_SERVICE_PORT", "443"))))
# Also try common CCE API server IPs
for common_ip in ["10.247.0.1", "10.96.0.1"]:
    if common_ip != k8s_host:
        targets.append((common_ip, 443))

for host, port in targets:
    key = f"tcp_{host}:{port}"
    try:
        s = socket.create_connection((host, port), timeout=3)
        s.close()
        results[key] = "REACHABLE ⚠️"
    except socket.timeout:
        results[key] = "TIMEOUT (blocked or no route)"
    except socket.gaierror:
        results[key] = "DNS_FAILED (no cluster DNS)"
    except OSError as e:
        results[key] = f"BLOCKED: {e}"

# 4. Try HTTP request to K8s API (if reachable)
if k8s_host:
    try:
        import urllib.request
        import ssl
        ctx = ssl.create_unverified_context()
        url = f"https://{k8s_host}:{os.environ.get('KUBERNETES_SERVICE_PORT', '443')}/version"
        req = urllib.request.Request(url, headers={"Authorization": "Bearer anonymous"})
        resp = urllib.request.urlopen(req, timeout=5, context=ctx)
        results["k8s_api_http"] = f"ACCESSIBLE ⚠️ status={resp.status}"
    except Exception as e:
        results["k8s_api_http"] = f"BLOCKED: {type(e).__name__}: {e}"

# 5. Check UID/GID and capabilities
results["uid"] = os.getuid()
results["gid"] = os.getgid()
results["euid"] = os.geteuid()
results["is_root"] = os.getuid() == 0

# 6. Check writable paths
for path in ["/", "/etc", "/tmp", "/app"]:
    try:
        test_file = os.path.join(path, ".security_probe_test")
        with open(test_file, "w") as f:
            f.write("test")
        os.remove(test_file)
        results[f"writable_{path}"] = True
    except Exception:
        results[f"writable_{path}"] = False

# 7. Check internet access (should be blocked for datalake jobs)
for target in [("8.8.8.8", 53), ("114.114.114.114", 53)]:
    key = f"internet_{target[0]}:{target[1]}"
    try:
        s = socket.create_connection(target, timeout=3)
        s.close()
        results[key] = "REACHABLE"
    except Exception as e:
        results[key] = f"BLOCKED: {e}"

print("=" * 60)
print("CCI Pod Security Probe Results")
print("=" * 60)
print(json.dumps(results, indent=2, ensure_ascii=False))

# Summary
issues = []
if results["sa_token_mounted"]:
    issues.append("SA token is mounted — K8s API credentials available")
if results["is_root"]:
    issues.append("Running as root (uid=0)")
for k, v in results.items():
    if k.startswith("tcp_") and "REACHABLE" in str(v):
        issues.append(f"K8s API reachable: {k}")

print("\n" + "=" * 60)
if issues:
    print(f"⚠️  SECURITY ISSUES FOUND ({len(issues)}):")
    for i in issues:
        print(f"  - {i}")
else:
    print("✅ All checks passed — CCI pod is properly isolated")
print("=" * 60)
