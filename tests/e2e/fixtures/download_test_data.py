#!/usr/bin/env python3
"""
Download small test datasets for pipeline E2E tests.

Text: Wikitext-2-raw-v1 subset (HuggingFace)
Video: Big Buck Bunny clips (public domain, from Blender Foundation)

Usage:
    python download_test_data.py

All files are saved to the same directory as this script.
"""
import os
import json
import urllib.request
import ssl

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# Disable SSL verification for downloads (some corporate proxies)
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE


def download_file(url: str, dest: str):
    """Download a file if it doesn't already exist."""
    if os.path.exists(dest):
        print(f"  [skip] {os.path.basename(dest)} already exists")
        return
    print(f"  Downloading {os.path.basename(dest)}...")
    opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx))
    with opener.open(url) as resp, open(dest, "wb") as f:
        f.write(resp.read())
    size_mb = os.path.getsize(dest) / (1024 * 1024)
    print(f"  [done] {size_mb:.1f} MB")


def download_text_dataset():
    """Download a small text dataset from HuggingFace datasets."""
    print("\n=== Text Dataset ===")
    dest = os.path.join(SCRIPT_DIR, "wikitext_sample.jsonl")
    if os.path.exists(dest):
        print(f"  [skip] wikitext_sample.jsonl already exists")
        return

    # Use HuggingFace datasets API to get a small sample
    url = "https://huggingface.co/datasets/Salesforce/wikitext/resolve/main/wikitext-2-raw-v1/test-00000-of-00001.parquet"
    parquet_path = os.path.join(SCRIPT_DIR, "_wikitext_test.parquet")

    try:
        download_file(url, parquet_path)
        # Convert parquet to JSONL (requires pyarrow)
        import pyarrow.parquet as pq
        table = pq.read_table(parquet_path)
        texts = table.to_pydict()["text"]

        # Filter: keep non-empty paragraphs > 100 chars, take first 50
        records = []
        for i, text in enumerate(texts):
            text = text.strip()
            if len(text) > 100 and not text.startswith("="):
                records.append({"id": f"wiki_{len(records):04d}", "content": text,
                                "source": "wikitext-2", "lang": "en"})
                if len(records) >= 50:
                    break

        with open(dest, "w") as f:
            for r in records:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"  [done] {len(records)} text records saved to wikitext_sample.jsonl")

    except ImportError:
        print("  [warn] pyarrow not installed, using fallback sample_texts.jsonl")
    finally:
        if os.path.exists(parquet_path):
            os.remove(parquet_path)


def download_video_dataset():
    """Download small public domain video clips for testing."""
    print("\n=== Video Dataset ===")

    # Generate test videos with ffmpeg (deterministic, no network required)
    import subprocess, shutil

    if not shutil.which("ffmpeg"):
        print("  [warn] ffmpeg not found, creating minimal MP4 placeholders")
        _create_minimal_mp4(os.path.join(SCRIPT_DIR, "sample_video_720p.mp4"))
        _create_minimal_mp4(os.path.join(SCRIPT_DIR, "sample_video_360p.mp4"))
        return

    videos = [
        {
            "name": "sample_video_720p.mp4",
            "cmd": [
                "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=5:size=1280x720:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=5",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28",
                "-c:a", "aac", "-b:a", "64k", "-pix_fmt", "yuv420p",
            ],
            "desc": "Test pattern 5s 720p (~180KB)",
        },
        {
            "name": "sample_video_360p.mp4",
            "cmd": [
                "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc2=duration=8:size=640x360:rate=24",
                "-f", "lavfi", "-i", "sine=frequency=880:duration=8",
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "30",
                "-c:a", "aac", "-b:a", "48k", "-pix_fmt", "yuv420p",
            ],
            "desc": "Test pattern 8s 360p (~1.2MB)",
        },
    ]

    for v in videos:
        dest = os.path.join(SCRIPT_DIR, v["name"])
        if os.path.exists(dest):
            print(f"  [skip] {v['name']} already exists")
            continue
        print(f"  Generating {v['desc']}...")
        try:
            subprocess.run(v["cmd"] + [dest], capture_output=True, check=True, timeout=30)
            size_mb = os.path.getsize(dest) / (1024 * 1024)
            print(f"  [done] {size_mb:.1f} MB")
        except Exception as e:
            print(f"  [error] {e}")
            _create_minimal_mp4(dest)


def _create_minimal_mp4(path: str):
    """Create a minimal valid MP4 file for API-layer testing."""
    if os.path.exists(path):
        return
    # Minimal ftyp + moov MP4 (valid but no actual video content)
    # This is sufficient for testing file upload / pipeline API,
    # but won't work with actual ffmpeg processing
    import struct
    with open(path, "wb") as f:
        # ftyp box
        ftyp = b"isom\x00\x00\x00\x00isomiso2mp41"
        f.write(struct.pack(">I", len(ftyp) + 8))
        f.write(b"ftyp")
        f.write(ftyp)
        # minimal moov box
        moov = b"\x00\x00\x00\x08mvhd"
        f.write(struct.pack(">I", len(moov) + 8))
        f.write(b"moov")
        f.write(moov)
    print(f"  [fallback] Created minimal MP4 placeholder: {os.path.basename(path)}")


def download_chinese_text():
    """Create a Chinese text sample from THUCNews-style data."""
    print("\n=== Chinese Text Dataset ===")
    dest = os.path.join(SCRIPT_DIR, "chinese_texts.jsonl")
    if os.path.exists(dest):
        print(f"  [skip] chinese_texts.jsonl already exists")
        return

    # Pre-built Chinese text samples covering different topics
    samples = [
        {"id": "zh001", "content": "近年来，人工智能技术在医疗领域的应用取得了显著进展。深度学习算法在医学影像诊断中展现出与专业医生相当甚至更高的准确率，特别是在肺部CT扫描、眼底检查和皮肤病变识别等方面。这些技术不仅提高了诊断效率，还能帮助偏远地区的患者获得高质量的医疗服务。", "source": "tech-news", "lang": "zh"},
        {"id": "zh002", "content": "量子计算是一个正在快速发展的前沿技术领域。与经典计算机使用比特不同，量子计算机使用量子比特，可以同时处于多个状态的叠加。谷歌、IBM和中国的研究团队都在这一领域取得了重要突破。量子计算有望在密码学、药物发现和材料科学等领域带来革命性的变化。", "source": "tech-news", "lang": "zh"},
        {"id": "zh003", "content": "数据湖是一种大规模存储系统，可以以原始格式存储各种类型的数据，包括结构化、半结构化和非结构化数据。与数据仓库不同，数据湖采用写时模式，数据在存储时不需要预先定义模式。Apache Iceberg和Delta Lake是目前最流行的开源数据湖格式。", "source": "tutorial", "lang": "zh"},
        {"id": "zh004", "content": "自动驾驶技术的核心挑战之一是在复杂的城市环境中进行准确的感知和决策。激光雷达、摄像头和毫米波雷达的多传感器融合方案是目前主流的技术路线。特斯拉坚持纯视觉方案，而Waymo等公司则采用多传感器融合的方法。安全性和可靠性仍然是自动驾驶商业化的最大障碍。", "source": "tech-news", "lang": "zh"},
        {"id": "zh005", "content": "开源大语言模型的发展正在改变AI行业的格局。从Meta的LLaMA系列到Mistral、Qwen等模型，开源社区正在缩小与闭源模型之间的差距。微调技术如LoRA和QLoRA使得在消费级GPU上训练大模型成为可能，极大地降低了AI应用的门槛。", "source": "analysis", "lang": "zh"},
        {"id": "zh006", "content": "云原生架构已经成为现代软件开发的主流范式。Kubernetes作为容器编排的事实标准，提供了自动化部署、扩展和管理容器化应用的能力。Service Mesh、Serverless和GitOps等技术进一步推动了云原生生态的发展，使开发团队能够更快速、更可靠地交付软件。", "source": "tutorial", "lang": "zh"},
        {"id": "zh007", "content": "向量数据库在AI应用中扮演着越来越重要的角色。随着大语言模型和检索增强生成（RAG）技术的普及，高效的向量检索能力变得至关重要。Milvus、Pinecone、Weaviate和pgvector等解决方案各有特色，适用于不同的使用场景和规模需求。", "source": "tutorial", "lang": "zh"},
        {"id": "zh008", "content": "半导体行业的地缘政治博弈正在重塑全球供应链格局。台积电、三星和英特尔在先进制程节点上的竞争日趋激烈。各国政府纷纷出台芯片产业支持政策，力图在这一关键技术领域确保供应链安全。芯片设计和制造的分离模式正面临新的挑战和机遇。", "source": "news", "lang": "zh"},
        {"id": "zh009", "content": "联邦学习是一种分布式机器学习方法，允许多方在不共享原始数据的前提下协同训练模型。这种技术在医疗健康、金融风控等对数据隐私要求严格的领域具有广阔的应用前景。差分隐私和安全多方计算等技术为联邦学习提供了更强的隐私保护能力。", "source": "paper", "lang": "zh"},
        {"id": "zh010", "content": "可观测性是现代分布式系统运维的关键能力。它通过日志、指标和链路追踪三大支柱，帮助开发和运维团队理解系统的内部状态。OpenTelemetry正在成为可观测性领域的统一标准，Prometheus、Grafana和Jaeger等工具构成了完整的可观测性技术栈。", "source": "tutorial", "lang": "zh"},
    ]

    with open(dest, "w") as f:
        for s in samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    print(f"  [done] {len(samples)} Chinese text records saved to chinese_texts.jsonl")


if __name__ == "__main__":
    print("Downloading test datasets for pipeline E2E tests...")
    download_text_dataset()
    download_chinese_text()
    download_video_dataset()
    print("\nDone! All test data saved to:", SCRIPT_DIR)
