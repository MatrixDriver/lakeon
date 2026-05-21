package creds

import (
	"fmt"
	"os"
	"path/filepath"

	"gopkg.in/yaml.v3"
)

type Credentials struct {
	OBS struct {
		Endpoint  string `yaml:"endpoint"`
		AccessKey string `yaml:"access_key"`
		SecretKey string `yaml:"secret_key"`
		Bucket    string `yaml:"bucket"`
	} `yaml:"obs"`
	Pageserver struct {
		MgmtEndpoint string `yaml:"mgmt_endpoint"`
		Token        string `yaml:"token"`
	} `yaml:"pageserver"`
	RDS struct {
		DefaultDSN string `yaml:"default_dsn"`
	} `yaml:"rds"`
}

func Load(path string) (*Credentials, error) {
	if path == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return nil, err
		}
		path = filepath.Join(home, ".dbay", "rescue-credentials.yaml")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read creds %s: %w", path, err)
	}
	var c Credentials
	if err := yaml.Unmarshal(b, &c); err != nil {
		return nil, fmt.Errorf("parse creds: %w", err)
	}
	return &c, nil
}
