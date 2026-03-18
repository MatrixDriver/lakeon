"""Structure-aware document chunking."""
import re
import logging
from typing import List, Dict, Any

logger = logging.getLogger(__name__)

MAX_CHUNK_TOKENS = 400
OVERLAP_RATIO = 0.15

def chunk_document(markdown: str, filename: str, format: str) -> List[Dict[str, Any]]:
    """Split markdown into structure-aware chunks with metadata."""
    sections = _split_by_headings(markdown)
    chunks = []
    chunk_index = 0

    for section in sections:
        heading = section["heading"]
        content = section["content"].strip()
        if not content:
            continue

        blocks = _split_into_blocks(content)
        current_chunk = ""

        for block in blocks:
            if block.startswith("```") or block.startswith("|"):
                if current_chunk.strip():
                    chunks.append(_make_chunk(current_chunk.strip(), heading, filename, format, chunk_index))
                    chunk_index += 1
                chunks.append(_make_chunk(block, heading, filename, format, chunk_index))
                chunk_index += 1
                current_chunk = ""
                continue

            combined = (current_chunk + "\n\n" + block).strip() if current_chunk else block
            if _estimate_tokens(combined) > MAX_CHUNK_TOKENS and current_chunk.strip():
                chunks.append(_make_chunk(current_chunk.strip(), heading, filename, format, chunk_index))
                chunk_index += 1
                overlap = _get_overlap(current_chunk)
                current_chunk = overlap + "\n\n" + block if overlap else block
            else:
                current_chunk = combined

        if current_chunk.strip():
            chunks.append(_make_chunk(current_chunk.strip(), heading, filename, format, chunk_index))
            chunk_index += 1

    logger.info(f"Chunked '{filename}' into {len(chunks)} chunks")
    return chunks

def _split_by_headings(markdown: str) -> List[Dict[str, str]]:
    lines = markdown.split("\n")
    sections = []
    current_heading = ""
    current_lines = []
    for line in lines:
        if re.match(r"^#{1,3}\s+", line):
            if current_lines:
                sections.append({"heading": current_heading, "content": "\n".join(current_lines)})
            current_heading = line.strip().lstrip("#").strip()
            current_lines = []
        else:
            current_lines.append(line)
    if current_lines:
        sections.append({"heading": current_heading, "content": "\n".join(current_lines)})
    return sections

def _split_into_blocks(content: str) -> List[str]:
    blocks = []
    lines = content.split("\n")
    current_block = []
    in_code = False
    for line in lines:
        if line.startswith("```"):
            if in_code:
                current_block.append(line)
                blocks.append("\n".join(current_block))
                current_block = []
                in_code = False
            else:
                if current_block:
                    text = "\n".join(current_block).strip()
                    if text:
                        blocks.append(text)
                    current_block = []
                current_block.append(line)
                in_code = True
        elif in_code:
            current_block.append(line)
        elif line.startswith("|"):
            current_block.append(line)
        elif not line.strip():
            if current_block:
                text = "\n".join(current_block).strip()
                if text:
                    blocks.append(text)
                current_block = []
        else:
            if current_block and current_block[-1].startswith("|") and not line.startswith("|"):
                blocks.append("\n".join(current_block))
                current_block = [line]
            else:
                current_block.append(line)
    if current_block:
        text = "\n".join(current_block).strip()
        if text:
            blocks.append(text)
    return blocks

def _make_chunk(content, section, filename, format, index):
    return {
        "content": content,
        "chunk_index": index,
        "metadata": {"filename": filename, "section": section, "format": format}
    }

def _estimate_tokens(text):
    cjk = sum(1 for c in text if '\u4e00' <= c <= '\u9fff')
    non_cjk = len(text) - cjk
    return int(non_cjk * 0.25 + cjk * 1.5)

def _get_overlap(text):
    target = int(len(text) * OVERLAP_RATIO)
    if target < 50:
        return ""
    return text[-target:]
