from dbay_branch_version.report import VENDOR_CLAIMS, render_comparison_markdown


def test_vendor_claims_use_official_urls():
    urls = [claim["url"] for claim in VENDOR_CLAIMS]

    assert "https://neon.com/docs/introduction/point-in-time-restore" in urls
    assert "https://xata.io/documentation/core-concepts" in urls
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
