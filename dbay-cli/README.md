# dbay-cli

Command-line tool for [DBay](https://dbay.cloud). This CLI targets the
Lakebase database and LakebaseFS surface provided by dbay.cloud.

Higher-level data intelligence commands live in the separate `dbay-agent`
project.

## Install

```bash
pip install dbay-cli
```

## Getting Started

```bash
# Login and create ~/.dbay/config.json
dbay login

# Work with Lakebase databases
dbay db list
dbay db create my-db

# Work with branches and versions
dbay branch list <database-id>
dbay version list <database-id> <branch-id>
```

## Configuration

`~/.dbay/config.json` is created by `dbay login`:

```json
{
  "endpoint": "https://api.dbay.cloud:8443",
  "api_key": "lk_..."
}
```

## Links

- [DBay Console](https://console.dbay.cloud)
- [DBay API](https://api.dbay.cloud:8443/api/v1)
