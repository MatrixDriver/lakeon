package obs

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	hwobs "github.com/huaweicloud/huaweicloud-sdk-go-obs/obs"
)

func TestTenantManifest_JSONRoundtrip(t *testing.T) {
	m := TenantManifest{
		ManifestVersion: 1,
		TenantID:        "tn1",
		OwnerEmail:      "alice@example.com",
		CreatedAt:       time.Date(2026, 4, 1, 0, 0, 0, 0, time.UTC),
		UpdatedAt:       time.Date(2026, 5, 21, 14, 0, 0, 0, time.UTC),
		Version:         42,
		Databases: []DatabaseEntry{{
			DBID:       "db1",
			Name:       "mydb",
			TimelineID: "tl1",
			CreatedAt:  time.Date(2026, 4, 5, 0, 0, 0, 0, time.UTC),
			Branches:   []BranchEntry{{BranchID: "br_main", LSN: "0/0"}},
		}},
	}
	b, err := json.Marshal(m)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	js := string(b)

	wantSubstrings := []string{
		`"manifest_version":1`,
		`"tenant_id":"tn1"`,
		`"owner_email":"alice@example.com"`,
		`"created_at":`,
		`"updated_at":`,
		`"version":42`,
		`"databases":`,
		`"db_id":"db1"`,
		`"name":"mydb"`,
		`"timeline_id":"tl1"`,
		`"branches":`,
		`"branch_id":"br_main"`,
		`"lsn":"0/0"`,
	}
	for _, want := range wantSubstrings {
		if !strings.Contains(js, want) {
			t.Errorf("missing %q in marshalled JSON:\n%s", want, js)
		}
	}

	// `parent` is empty + omitempty, must NOT appear.
	if strings.Contains(js, `"parent"`) {
		t.Errorf("empty parent should be omitted, got: %s", js)
	}
	// `deleted_at` is nil + omitempty, must NOT appear.
	if strings.Contains(js, `"deleted_at"`) {
		t.Errorf("nil deleted_at should be omitted, got: %s", js)
	}

	var back TenantManifest
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if back.TenantID != "tn1" {
		t.Errorf("tenant_id roundtrip: got %q", back.TenantID)
	}
	if back.Version != 42 {
		t.Errorf("version roundtrip: got %d", back.Version)
	}
	if len(back.Databases) != 1 || back.Databases[0].DBID != "db1" {
		t.Errorf("databases roundtrip: %+v", back.Databases)
	}
	if len(back.Databases[0].Branches) != 1 || back.Databases[0].Branches[0].BranchID != "br_main" {
		t.Errorf("branches roundtrip: %+v", back.Databases[0].Branches)
	}
	if !back.UpdatedAt.Equal(m.UpdatedAt) {
		t.Errorf("updated_at roundtrip: got %v want %v", back.UpdatedAt, m.UpdatedAt)
	}
}

func TestDatabaseEntry_DeletedAtSerialized(t *testing.T) {
	deleted := time.Date(2026, 5, 20, 12, 0, 0, 0, time.UTC)
	d := DatabaseEntry{
		DBID:       "db9",
		Name:       "gone",
		TimelineID: "tl9",
		CreatedAt:  time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC),
		DeletedAt:  &deleted,
	}
	b, err := json.Marshal(d)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	js := string(b)
	if !strings.Contains(js, `"deleted_at":"2026-05-20T12:00:00Z"`) {
		t.Errorf("expected RFC3339 deleted_at, got: %s", js)
	}
}

func TestOwnersIndex_JSONRoundtrip(t *testing.T) {
	idx := OwnersIndex{
		IndexVersion: 1,
		UpdatedAt:    time.Date(2026, 5, 21, 14, 0, 0, 0, time.UTC),
		Owners: map[string][]string{
			"alice@example.com": {"tn1", "tn2"},
			"bob@example.com":   {"tn3"},
		},
	}
	b, err := json.Marshal(idx)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	js := string(b)

	for _, want := range []string{
		`"index_version":1`,
		`"updated_at":`,
		`"owners":`,
		`"alice@example.com"`,
		`"tn1"`,
	} {
		if !strings.Contains(js, want) {
			t.Errorf("missing %q in JSON: %s", want, js)
		}
	}

	var back OwnersIndex
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if back.IndexVersion != 1 {
		t.Errorf("index_version roundtrip: got %d", back.IndexVersion)
	}
	if len(back.Owners["alice@example.com"]) != 2 {
		t.Errorf("owners alice roundtrip: %+v", back.Owners)
	}
	if len(back.Owners["bob@example.com"]) != 1 || back.Owners["bob@example.com"][0] != "tn3" {
		t.Errorf("owners bob roundtrip: %+v", back.Owners)
	}
}

func TestIsNotFound(t *testing.T) {
	if IsNotFound(nil) {
		t.Errorf("nil err should not be NotFound")
	}
	if IsNotFound(errors.New("connection refused")) {
		t.Errorf("plain error should not be NotFound")
	}
	if !IsNotFound(hwobs.ObsError{Status: "404 Not Found", Code: "NoSuchKey"}) {
		t.Errorf("404 ObsError should be NotFound")
	}
	if !IsNotFound(hwobs.ObsError{Status: "404 Not Found"}) {
		t.Errorf("Status=404 ObsError should be NotFound")
	}
	if !IsNotFound(hwobs.ObsError{Code: "NoSuchKey"}) {
		t.Errorf("Code=NoSuchKey ObsError should be NotFound")
	}
	if IsNotFound(hwobs.ObsError{Status: "500 Internal Server Error", Code: "InternalError"}) {
		t.Errorf("500 ObsError should NOT be NotFound")
	}
	// Wrapped error fallback path.
	wrapped := errors.New("delete x: obs: service returned error: Status=404 Not Found, Code=NoSuchKey")
	if !IsNotFound(wrapped) {
		t.Errorf("wrapped 404 string error should be NotFound (fallback)")
	}
}

// Compile-time assertion: TenantManifest must JSON-encode with the exact
// snake_case keys the Java side writes. If anyone renames a tag, the
// roundtrip tests above will catch it; this assertion documents intent.
var _ = []byte(`{"manifest_version":0,"tenant_id":"","owner_email":"","created_at":"","updated_at":"","version":0}`)
