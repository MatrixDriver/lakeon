package creds

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoad_ParsesYAML(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "creds.yaml")
	yaml := `
obs:
  endpoint: obs.cn-east-3.myhuaweicloud.com
  access_key: AK_TEST
  secret_key: SK_TEST
  bucket: test-bucket
pageserver:
  mgmt_endpoint: https://pageserver:9898
  token: tok_test
rds:
  default_dsn: postgres://localhost/test
`
	if err := os.WriteFile(path, []byte(yaml), 0600); err != nil {
		t.Fatal(err)
	}
	c, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if c.OBS.Bucket != "test-bucket" {
		t.Errorf("bucket: got %q", c.OBS.Bucket)
	}
	if c.Pageserver.Token != "tok_test" {
		t.Errorf("token mismatch")
	}
	if c.RDS.DefaultDSN == "" {
		t.Error("dsn missing")
	}
}
