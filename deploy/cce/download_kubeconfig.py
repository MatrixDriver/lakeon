import hashlib, hmac, json, os, re, sys, time
import urllib.request, urllib.parse, urllib.error
from datetime import datetime, timezone

REGION = "cn-south-1"
ENV_FILE = "d:/Google Driver/lakeon/deploy/cce/.env.cce"
KUBECONFIG_PATH = "C:/Users/raoli/.kube/cce-lakeon-config"

def load_credentials():
    creds = {}
    with open(ENV_FILE, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if "=" in line and not line.startswith("#"):
                line = line.removeprefix("export ").strip()
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

def api(method, url, ak, sk, body="", timeout=30):
    parsed = urllib.parse.urlparse(url)
    host = parsed.hostname
    # V3 API Signature Pitfall:
    # 1. Canonical URI must end with / for collection resources
    # 2. Canonical URI must NOT end with / for specific resources/actions
    path = parsed.path
    # DO NOT add trailing slash here manually if not in URL
    
    query = parsed.query or ""
    if query:
        params = urllib.parse.parse_qsl(query, keep_blank_values=True)
        params.sort(key=lambda x: x[0])
        query = urllib.parse.urlencode(params)

    now = datetime.now(timezone.utc)
    ts = now.strftime("%Y%m%dT%H%M%SZ")
    ds = now.strftime("%Y%m%d")

    svc = host.split(".")[0]
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
    if body:
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=body.encode() if body else None, headers=headers, method=method)
    try:
        import ssl
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read().decode())
        except: return e.code, {"error": str(e)}
    except Exception as e:
        return 0, {"error": str(e)}

ak, sk = load_credentials()
pid = "435f7a2e42cc4880be54a27de2d5c427"
cid = "d11cb57b-18f3-11f1-a0e9-0255ac1000b3"

print(f"Fetching Kubeconfig for cluster {cid}...")
# TRY WITH TRAILING SLASH - common cause of 401 in CCE
url_with_slash = f"https://cce.cn-south-1.myhuaweicloud.com/api/v3/projects/{pid}/clusters/{cid}/clustercert/"
payload = json.dumps({"duration": -1})

print("Trying with trailing slash...")
status, response = api("POST", url_with_slash, ak, sk, payload)

if status not in (200, 201):
    print("Trying WITHOUT trailing slash...")
    url_no_slash = f"https://cce.cn-south-1.myhuaweicloud.com/api/v3/projects/{pid}/clusters/{cid}/clustercert"
    status, response = api("POST", url_no_slash, ak, sk, payload)

if status in (200, 201):
    if response.get("kind") == "Config":
        with open(KUBECONFIG_PATH, "w", encoding='utf-8') as f:
            f.write(json.dumps(response, indent=2))
        print(f"✅ Success! Kubeconfig updated to {KUBECONFIG_PATH}")
    else:
        print("❓ Unexpected response format:")
        print(json.dumps(response, indent=2))
else:
    print(f"❌ Error: Status {status}")
    print(json.dumps(response, indent=2, ensure_ascii=False))
