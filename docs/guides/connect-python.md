# Python 连接指南

## psycopg2（最常用）

### 安装

```bash
pip install psycopg2-binary
```

### 连接

```python
import psycopg2

conn = psycopg2.connect(
    "postgres://user_xxx:PASSWORD@pg.dbay.cloud:4432/my-app-db?options=endpoint%3Dmy-app-db&sslmode=require"
)

cur = conn.cursor()
cur.execute("SELECT version()")
print(cur.fetchone())

cur.execute("""
    CREATE TABLE IF NOT EXISTS todos (
        id SERIAL PRIMARY KEY,
        title TEXT NOT NULL,
        done BOOLEAN DEFAULT FALSE,
        created_at TIMESTAMPTZ DEFAULT NOW()
    )
""")
cur.execute("INSERT INTO todos (title) VALUES (%s) RETURNING id", ("Learn DBay",))
todo_id = cur.fetchone()[0]
print(f"Created todo #{todo_id}")

conn.commit()
cur.close()
conn.close()
```

## SQLAlchemy

### 安装

```bash
pip install sqlalchemy psycopg2-binary
```

### 连接

```python
from sqlalchemy import create_engine, text

DATABASE_URL = "postgresql://user_xxx:PASSWORD@pg.dbay.cloud:4432/my-app-db?options=endpoint%3Dmy-app-db&sslmode=require"

engine = create_engine(DATABASE_URL)

with engine.connect() as conn:
    result = conn.execute(text("SELECT version()"))
    print(result.fetchone())
```

### ORM 模型

```python
from sqlalchemy import Column, Integer, String, Boolean, DateTime, func
from sqlalchemy.orm import declarative_base, Session

Base = declarative_base()

class Todo(Base):
    __tablename__ = "todos"
    id = Column(Integer, primary_key=True)
    title = Column(String, nullable=False)
    done = Column(Boolean, default=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

# 创建表
Base.metadata.create_all(engine)

# CRUD
with Session(engine) as session:
    todo = Todo(title="Deploy to DBay")
    session.add(todo)
    session.commit()

    todos = session.query(Todo).all()
    for t in todos:
        print(f"#{t.id} {t.title} (done={t.done})")
```

## Django

`settings.py`:
```python
DATABASES = {
    'default': {
        'ENGINE': 'django.db.backends.postgresql',
        'NAME': 'my-app-db',
        'USER': 'user_xxx',
        'PASSWORD': 'PASSWORD',
        'HOST': 'pg.dbay.cloud',
        'PORT': '4432',
        'OPTIONS': {
            'options': '-c endpoint=my-app-db',
            'sslmode': 'require',
        },
    }
}
```

## 注意事项

- 连接串中 `options=endpoint%3D<db-name>` 是必须的
- 首次连接休眠数据库有 ~3 秒唤醒延迟
- 生产环境建议使用连接池（SQLAlchemy 默认 pool_size=5）
