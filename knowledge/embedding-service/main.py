import os
import logging
from typing import List
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer, CrossEncoder

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

MODEL_NAME = os.environ.get("MODEL_NAME", "BAAI/bge-m3")
BATCH_SIZE = int(os.environ.get("BATCH_SIZE", "32"))
RERANK_MODEL_NAME = os.environ.get("RERANK_MODEL_NAME", "BAAI/bge-reranker-v2-m3")

app = FastAPI()
model = None
rerank_model = None

class EmbedRequest(BaseModel):
    texts: List[str]

class EmbedResponse(BaseModel):
    embeddings: List[List[float]]

@app.on_event("startup")
async def load_models():
    global model, rerank_model
    logger.info(f"Loading model {MODEL_NAME}...")
    model = SentenceTransformer(MODEL_NAME)
    logger.info(f"Model loaded. Dimension: {model.get_sentence_embedding_dimension()}")
    if os.environ.get("RERANK_ENABLED", "true").lower() == "true":
        logger.info(f"Loading reranker {RERANK_MODEL_NAME}...")
        rerank_model = CrossEncoder(RERANK_MODEL_NAME)
        logger.info(f"Reranker loaded: {RERANK_MODEL_NAME}")

@app.get("/health")
def health():
    return {
        "status": "ok",
        "model": MODEL_NAME,
        "ready": model is not None,
        "rerank_model": RERANK_MODEL_NAME,
        "rerank_ready": rerank_model is not None,
    }

@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if not req.texts:
        return EmbedResponse(embeddings=[])
    embeddings = model.encode(req.texts, batch_size=BATCH_SIZE, normalize_embeddings=True)
    return EmbedResponse(embeddings=embeddings.tolist())

@app.post("/rerank")
async def rerank(request: dict):
    if rerank_model is None:
        raise HTTPException(status_code=503, detail="Reranker not loaded")
    query = request["query"]
    passages = request["passages"]
    top_k = request.get("top_k", len(passages))
    pairs = [[query, p] for p in passages]
    scores = rerank_model.predict(pairs).tolist()
    ranked = sorted(enumerate(scores), key=lambda x: x[1], reverse=True)[:top_k]
    rankings = [idx for idx, _ in ranked]
    ranked_scores = [score for _, score in ranked]
    return {"rankings": rankings, "scores": ranked_scores}
