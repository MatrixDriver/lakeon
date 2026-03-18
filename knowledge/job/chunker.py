"""Structure-aware document chunking with offset tracking and quality metrics."""
import re
import logging
from typing import List, Dict, Any

logger = logging.getLogger(__name__)

MAX_CHUNK_TOKENS = 400
OVERLAP_RATIO = 0.15

def chunk_document(markdown: str, filename: str, format: str,
                   max_tokens: int = MAX_CHUNK_TOKENS,
                   overlap_ratio: float = OVERLAP_RATIO) -> List[Dict[str, Any]]:
    """Split markdown into structure-aware chunks with metadata and character offsets."""
    sections = _split_by_headings(markdown)
    chunks = []
    chunk_index = 0

    for section in sections:
        heading = section["heading"]
        content = section["content"].strip()
        content_start = section["offset"]  # absolute offset of section content in markdown
        if not content:
            continue

        blocks = _split_into_blocks(content)
        current_chunk = ""
        current_chunk_start = None  # offset in markdown for current accumulated chunk
        search_start = 0  # tracks position in content to avoid matching earlier occurrences

        for block in blocks:
            # Find this block's position within the section content
            block_offset_in_content = content.find(block, search_start)
            if block_offset_in_content < 0:
                block_offset_in_content = content.find(block)
            if block_offset_in_content >= 0:
                search_start = block_offset_in_content + len(block)
            block_abs_start = content_start + block_offset_in_content if block_offset_in_content >= 0 else None

            if block.startswith("```") or block.startswith("|"):
                if current_chunk.strip():
                    chunk_start = current_chunk_start if current_chunk_start is not None else 0
                    chunk_end = chunk_start + len(current_chunk.strip())
                    overlap_prev = _compute_overlap(chunks, current_chunk.strip())
                    chunks.append(_make_chunk(current_chunk.strip(), heading, filename, format,
                                             chunk_index, chunk_start, chunk_end, overlap_prev))
                    chunk_index += 1
                # Code/table block as its own chunk
                block_start = block_abs_start if block_abs_start is not None else 0
                block_end = block_start + len(block)
                overlap_prev = _compute_overlap(chunks, block)
                chunks.append(_make_chunk(block, heading, filename, format,
                                         chunk_index, block_start, block_end, overlap_prev))
                chunk_index += 1
                current_chunk = ""
                current_chunk_start = None
                continue

            combined = (current_chunk + "\n\n" + block).strip() if current_chunk else block
            if _estimate_tokens(combined) > max_tokens and current_chunk.strip():
                chunk_start = current_chunk_start if current_chunk_start is not None else 0
                chunk_end = chunk_start + len(current_chunk.strip())
                overlap_prev = _compute_overlap(chunks, current_chunk.strip())
                chunks.append(_make_chunk(current_chunk.strip(), heading, filename, format,
                                         chunk_index, chunk_start, chunk_end, overlap_prev))
                chunk_index += 1
                overlap = _get_overlap(current_chunk, overlap_ratio)
                if overlap:
                    current_chunk = overlap + "\n\n" + block
                    # The overlap start is near the end of the previous chunk
                    current_chunk_start = chunk_end - len(overlap)
                else:
                    current_chunk = block
                    current_chunk_start = block_abs_start
            else:
                if not current_chunk:
                    current_chunk_start = block_abs_start
                current_chunk = combined

        if current_chunk.strip():
            chunk_start = current_chunk_start if current_chunk_start is not None else 0
            chunk_end = chunk_start + len(current_chunk.strip())
            overlap_prev = _compute_overlap(chunks, current_chunk.strip())
            chunks.append(_make_chunk(current_chunk.strip(), heading, filename, format,
                                     chunk_index, chunk_start, chunk_end, overlap_prev))
            chunk_index += 1

    logger.info(f"Chunked '{filename}' into {len(chunks)} chunks")
    return chunks

def detect_duplicates(chunks: List[Dict], embeddings: List[List[float]], threshold: float = 0.92) -> None:
    """Mutates chunks in-place, adding duplicate_of/similarity to metadata."""
    if len(chunks) < 3:
        return
    import numpy as np
    emb_matrix = np.array(embeddings)
    norms = np.linalg.norm(emb_matrix, axis=1, keepdims=True)
    norms = np.where(norms == 0, 1, norms)  # avoid division by zero
    emb_normed = emb_matrix / norms
    sim_matrix = emb_normed @ emb_normed.T
    for i in range(len(chunks)):
        for j in range(i + 2, len(chunks)):  # skip adjacent (i+1)
            if sim_matrix[i][j] > threshold:
                chunks[j]["metadata"]["duplicate_of"] = chunks[i]["chunk_index"]
                chunks[j]["metadata"]["similarity"] = round(float(sim_matrix[i][j]), 4)
                break  # only mark the first duplicate match

def assign_pages(chunks: List[Dict], page_metadata: List[Dict]) -> None:
    """Sets page_start and page_end on each chunk based on char_offset and page_metadata."""
    if not page_metadata:
        for chunk in chunks:
            chunk["page_start"] = None
            chunk["page_end"] = None
        return

    for chunk in chunks:
        start = chunk["char_offset_start"]
        end = chunk["char_offset_end"]
        chunk["page_start"] = None
        chunk["page_end"] = None
        for pm in page_metadata:
            if pm["char_start"] <= start < pm["char_end"]:
                chunk["page_start"] = pm["page"]
            if pm["char_start"] < end <= pm["char_end"]:
                chunk["page_end"] = pm["page"]

def _split_by_headings(markdown: str) -> List[Dict[str, Any]]:
    """Split markdown by headings, tracking character offsets."""
    lines = markdown.split("\n")
    sections = []
    current_heading = ""
    current_lines = []
    heading_end_offset = 0

    char_pos = 0
    for i, line in enumerate(lines):
        if re.match(r"^#{1,3}\s+", line):
            if current_lines:
                sections.append({
                    "heading": current_heading,
                    "content": "\n".join(current_lines),
                    "offset": heading_end_offset,
                })
            current_heading = line.strip().lstrip("#").strip()
            current_lines = []
            heading_end_offset = char_pos + len(line) + 1  # +1 for newline
        else:
            if not current_lines and not sections:
                heading_end_offset = char_pos
            current_lines.append(line)
        char_pos += len(line) + 1  # +1 for newline separator

    if current_lines:
        sections.append({
            "heading": current_heading,
            "content": "\n".join(current_lines),
            "offset": heading_end_offset,
        })
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

def _make_chunk(content, section, filename, format, index, char_offset_start, char_offset_end, overlap_prev):
    return {
        "content": content,
        "chunk_index": index,
        "char_offset_start": char_offset_start,
        "char_offset_end": char_offset_end,
        "char_count": len(content),
        "overlap_prev": overlap_prev,
        "page_start": None,
        "page_end": None,
        "metadata": {"filename": filename, "section": section, "format": format}
    }

def _compute_overlap(chunks: List[Dict], current_content: str) -> int:
    """Compute number of overlapping characters between the end of the previous chunk and the start of the current chunk."""
    if not chunks:
        return 0
    prev_content = chunks[-1]["content"]
    # Check how much of the tail of prev_content matches the head of current_content
    max_overlap = min(len(prev_content), len(current_content))
    for length in range(max_overlap, 0, -1):
        if prev_content[-length:] == current_content[:length]:
            return length
    return 0

def _estimate_tokens(text):
    cjk = sum(1 for c in text if '\u4e00' <= c <= '\u9fff')
    non_cjk = len(text) - cjk
    return int(non_cjk * 0.25 + cjk * 1.5)

def _get_overlap(text, overlap_ratio=OVERLAP_RATIO):
    target = int(len(text) * overlap_ratio)
    if target < 50:
        return ""
    return text[-target:]
