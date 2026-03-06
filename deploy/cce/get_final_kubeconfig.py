import hashlib, hmac, json, os, re, sys, time
import urllib.request, urllib.parse, urllib.error
from datetime import datetime, timezone

REGION = "cn-south-1"
# THE DEFINITIVE CLUSTER ID FROM .lakeon-cloud.json
CLUSTER_ID = "d0b979d8-18f3-11f1-a0e9-0255ac1000b3"
PROJECT_ID = "435f7a2e42cc4880be54a27de2d5c427"
ENV_FILE = "d:/Google Driver/lakeon/deploy/cce/.env.cce"

def load_credentials():
    creds = {}
    with open(ENV_FILE, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                line = line.replace("export ", "").strip()
                key, val = line.split("=", 1)
                creds[key.strip()] = val.strip().strip("'\"")
    return creds.get("OBS_AK"), creds.get("OBS_SK")

def _hmac256(key, msg):
    if isinstance(key, str): key = key.encode()
    if isinstance(msg, str): msg = msg.encode()
    return hmac.new(key, msg, hashlib.sha256).digest()

def _sha256(data):
    if isinstance(data, str): data = data.encode()
    return hashlib.sha256(data).hexdigest()

def api(method, url, ak, sk, body=""):
    parsed = urllib.parse.urlparse(url)
    host = parsed.hostname
    path = parsed.path
    if not path.endswith("/"): path += "/" # HW CCE V3 style
    query = parsed.query or ""
    
    now = datetime.now(timezone.utc)
    ts = now.strftime("%Y%m%dT%H%M%SZ")
    ds = now.strftime("%Y%m%d")
    
    svc = "cce"
    rgn = REGION
    
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
    if body: headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body.encode() if body else None, headers=headers, method=method)
    try:
        import ssl
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        with urllib.request.urlopen(req, context=ctx) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        print(f"Error: {e.code}")
        print(e.read().decode())
        return e.code, {}

ak, sk = load_credentials()
print(f"Fetching Kubeconfig for cluster {CLUSTER_ID}...")
# CCE V3: /api/v3/projects/{project_id}/clusters/{cluster_id}/clustercert
payload = json.dumps({"duration": -1})
status, response = api("POST", f"https://cce.{REGION}.myhuaweicloud.com/api/v3/projects/{PROJECT_ID}/clusters/{CLUSTER_ID}/clustercert", ak, sk, payload)

if status in [200, 201]:
    with open("lakeon-final.kubeconfig", "w") as f:
        json.dump(response, f, indent=2)
    print("Success: saved to lakeon-final.kubeconfig")
else:
    print(f"Failed: {status} {response}")
