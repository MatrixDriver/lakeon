FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && \
    python -c "import duckdb; conn = duckdb.connect(); conn.execute('INSTALL postgres'); conn.execute('INSTALL httpfs'); conn.close()" && \
    python -m compileall -q /usr/local/lib/python3.12

COPY *.py .
RUN python -m compileall -q .

CMD ["python", "main.py"]
