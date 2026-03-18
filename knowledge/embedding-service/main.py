import os
import logging
from typing import List
from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

MODEL_NAME = os.environ.get("MODEL_NAME", "BAAI/bge-m3")
BATCH_SIZE = int(os.environ.get("BATCH_SIZE", "32"))

app = FastAPI()
model = None

class EmbedRequest(BaseModel):
    texts: List[str]

class EmbedResponse(BaseModel):
    embeddings: List[List[float]]

@app.on_event("startup")
def load_model():
    global model
    logger.info(f"Loading model {MODEL_NAME}...")
    model = SentenceTransformer(MODEL_NAME)
    logger.info(f"Model loaded. Dimension: {model.get_sentence_embedding_dimension()}")

@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME, "ready": model is not None}

@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if not req.texts:
        return EmbedResponse(embeddings=[])
    embeddings = model.encode(req.texts, batch_size=BATCH_SIZE, normalize_embeddings=True)
    return EmbedResponse(embeddings=embeddings.tolist())
