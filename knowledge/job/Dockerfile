FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && \
    python -c "import duckdb; conn = duckdb.connect(); conn.execute('INSTALL postgres'); conn.execute('INSTALL httpfs'); conn.close()"

COPY *.py .

CMD ["python", "main.py"]
