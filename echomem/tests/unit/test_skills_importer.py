import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.ollama_client import OllamaClient
from echomem.skills.importer import import_skills_from_directory


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


def _write_skill(dir, name, description, body):
    path = dir / f"{name}.md"
    path.write_text(f"---\nname: {name}\ndescription: {description}\n---\n\n{body}\n",
                    encoding="utf-8")


@pytest.mark.asyncio
async def test_imports_two_skills(tmp_path, httpx_mock, driver):
    skills_dir = tmp_path / "skills"
    skills_dir.mkdir()
    _write_skill(skills_dir, "tdd", "use test-driven development",
                 "## Steps\n1. write test\n2. fail\n3. impl\n4. pass\n5. commit\n")
    _write_skill(skills_dir, "git-commit", "commit small focused changes",
                 "## Steps\n1. stage what changed\n2. write a tight message\n3. commit\n")

    # Each skill triggers one embedding call
    httpx_mock.add_response(method="POST", url="http://ol:11434/api/embeddings",
                            json={"embedding": [1.0] + [0.0] * 1023}, is_reusable=True)

    async with OllamaClient("http://ol:11434") as ol:
        n = await import_skills_from_directory(driver, ol,
                                               directory=skills_dir,
                                               embedding_model="qwen3-embedding:0.6b",
                                               agent_scope="all")

    assert n == 2
    hits = driver.query_skills(query_emb=[1.0] + [0.0] * 1023, k=5)
    names = {h.name for h in hits}
    assert {"tdd", "git-commit"}.issubset(names)


@pytest.mark.asyncio
async def test_skips_files_without_frontmatter(tmp_path, httpx_mock, driver):
    skills_dir = tmp_path / "skills"
    skills_dir.mkdir()
    (skills_dir / "no-fm.md").write_text("# just a doc\n", encoding="utf-8")
    httpx_mock.add_response(method="POST", url="http://ol:11434/api/embeddings",
                            json={"embedding": [0.0] * 1024}, is_reusable=True, is_optional=True)

    async with OllamaClient("http://ol:11434") as ol:
        n = await import_skills_from_directory(driver, ol, directory=skills_dir,
                                               embedding_model="qwen3-embedding:0.6b")
    assert n == 0
