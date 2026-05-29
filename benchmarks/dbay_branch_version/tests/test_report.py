from dbay_branch_version.report import VENDOR_CLAIMS, render_comparison_markdown


def test_vendor_claims_use_official_urls():
    urls = [claim["url"] for claim in VENDOR_CLAIMS]

    assert {
        "https://neon.com/docs/introduction/point-in-time-restore",
        "https://neon.com/docs/ai/ai-database-versioning",
        "https://xata.io/documentation/core-concepts",
        "https://supabase.com/docs/guides/deployment/branching",
        "https://planetscale.com/docs/concepts/branching",
    }.issubset(urls)
    assert all(url.startswith("https://") for url in urls)


def test_render_comparison_separates_claims_from_measurements():
    markdown = render_comparison_markdown(
        bench_id="bench_1",
        environment={"api_base_url": "https://api.dbay.cloud:8443/api/v1"},
        summary={"branch/create/S": {"sample_count": 1, "error_rate": 0}},
        cleanup={"cleanup_status": "clean"},
    )

    assert "## DBay Measured Results" in markdown
    assert "## Vendor Public Claims" in markdown
    assert "bench_1" in markdown
    assert "cleanup_status" in markdown

    measured_heading_index = markdown.index("## DBay Measured Results")
    measured_key_index = markdown.index("branch/create/S")
    vendor_heading_index = markdown.index("## Vendor Public Claims")

    assert measured_heading_index < measured_key_index < vendor_heading_index

    measured_section = markdown[measured_heading_index:vendor_heading_index]
    vendor_section = markdown[vendor_heading_index:]

    assert "Neon" not in measured_section
    assert "PlanetScale" not in measured_section
    assert "https://neon.com/docs/introduction/point-in-time-restore" not in measured_section
    assert "https://planetscale.com/docs/concepts/branching" not in measured_section

    assert "Neon" in vendor_section
    assert "PlanetScale" in vendor_section
    assert "https://neon.com/docs/introduction/point-in-time-restore" in vendor_section
    assert "https://planetscale.com/docs/concepts/branching" in vendor_section
