"""Document parsing: PDF (Marker), DOCX (python-docx), Markdown (direct read)."""
import logging

logger = logging.getLogger(__name__)

def parse_document(file_path: str, format: str) -> str:
    """Parse a document and return Markdown text."""
    format = format.upper()
    if format == "PDF":
        return _parse_pdf(file_path)
    elif format == "DOCX":
        return _parse_docx(file_path)
    elif format == "MARKDOWN":
        return _parse_markdown(file_path)
    else:
        raise ValueError(f"Unsupported format: {format}")

def _parse_pdf(file_path: str) -> str:
    from marker.converters.pdf import PdfConverter
    from marker.models import create_model_dict
    models = create_model_dict()
    converter = PdfConverter(artifact_dict=models)
    rendered = converter(file_path)
    return rendered.markdown

def _parse_docx(file_path: str) -> str:
    from docx import Document
    doc = Document(file_path)
    parts = []
    for para in doc.paragraphs:
        style = para.style.name if para.style else ""
        text = para.text.strip()
        if not text:
            continue
        if "Heading 1" in style:
            parts.append(f"# {text}")
        elif "Heading 2" in style:
            parts.append(f"## {text}")
        elif "Heading 3" in style:
            parts.append(f"### {text}")
        else:
            parts.append(text)
    for table in doc.tables:
        rows = []
        for row in table.rows:
            cells = [cell.text.strip() for cell in row.cells]
            rows.append("| " + " | ".join(cells) + " |")
        if rows:
            header_sep = "| " + " | ".join(["---"] * len(table.rows[0].cells)) + " |"
            parts.append(rows[0])
            parts.append(header_sep)
            parts.extend(rows[1:])
    return "\n\n".join(parts)

def _parse_markdown(file_path: str) -> str:
    with open(file_path, "r", encoding="utf-8") as f:
        return f.read()
