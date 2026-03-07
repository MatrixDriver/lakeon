import subprocess
import os

SWR_REG = "swr.cn-south-1.myhuaweicloud.com"
ORG = "lakeon"
PREFIX = f"{SWR_REG}/{ORG}"

print(f"[{PREFIX}] Starting push process...")

def run_cmd(cmd, cwd=None, retries=3):
    print(f"\n> {' '.join(cmd)}")
    for i in range(retries):
        result = subprocess.run(cmd, cwd=cwd)
        if result.returncode == 0:
            return
        print(f"Warn: command failed with code {result.returncode}. Retrying ({i+1}/{retries})...")
        import time
        time.sleep(2)
    print(f"Error: command completely failed after {retries} retries.")
    exit(1)

def pull_tag_push(image, new_tag):
    run_cmd(["docker", "pull", image], retries=3)
    run_cmd(["docker", "tag", image, new_tag], retries=1)
    run_cmd(["docker", "push", new_tag], retries=3)
# 1. Base Images
pull_tag_push("busybox:1.36", f"{PREFIX}/busybox:1.36")
pull_tag_push("ghcr.io/neondatabase/neon:latest", f"{PREFIX}/neon:latest")
pull_tag_push("ghcr.io/neondatabase/compute-node-v17:latest", f"{PREFIX}/compute-node-v17:latest")

# 2. Lakeon Custom Apps
root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))

print("\n--- Building lakeon-api ---")
run_cmd(["docker", "build", "-t", f"{PREFIX}/lakeon-api:0.1.0", "lakeon-api"], cwd=root_dir)
run_cmd(["docker", "push", f"{PREFIX}/lakeon-api:0.1.0"], cwd=root_dir)

print("\n--- Building lakeon-admin ---")
run_cmd(["docker", "build", "-t", f"{PREFIX}/lakeon-admin:0.1.0", "lakeon-admin"], cwd=root_dir)
run_cmd(["docker", "push", f"{PREFIX}/lakeon-admin:0.1.0"], cwd=root_dir)

print("\n--- Building lakeon-console ---")
run_cmd(["docker", "build", "-t", f"{PREFIX}/lakeon-console:0.1.0", "lakeon-console"], cwd=root_dir)
run_cmd(["docker", "push", f"{PREFIX}/lakeon-console:0.1.0"], cwd=root_dir)

print("\nALL IMAGES PUSHED SUCCESSFULLY! Restarting Deployments...")
kubeconfig = "C:/Users/raoli/.kube/lakeon-cce-std-v3-new-kubeconfig.yaml"
env_vars = {}
with open("deploy/cce/.env.cce", "r") as f:
    for line in f:
        line = line.strip()
        if "=" in line and not line.startswith("#"):
            line = line.replace("export ", "")
            k, v = line.split("=", 1)
            env_vars[k.strip()] = v.strip().strip("'\"")

helm_cmd = [
    "helm", "upgrade", "--install", "lakeon", "deploy/helm/lakeon",
    "-f", "deploy/cce/values-cce.yaml", "-n", "lakeon", "--kubeconfig", kubeconfig,
    "--set", f"obs.accessKey={env_vars.get('OBS_AK')}",
    "--set", f"obs.secretKey={env_vars.get('OBS_SK')}",
    "--set", f"metadataDb.host={env_vars.get('RDS_PRIVATE_IP')}",
    "--set", f"metadataDb.password={env_vars.get('RDS_PASSWORD')}"
]
run_cmd(helm_cmd, cwd=root_dir)

print("\nWaiting for pods to be created and running... To check, use kubectl get pods")
