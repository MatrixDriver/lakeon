package neon

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestGetLsnByTimestamp_CallsCorrectURL(t *testing.T) {
	var capturedURL string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedURL = r.URL.String()
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"lsn":"0/A1B2C3D4"}`))
	}))
	defer srv.Close()

	c := New(srv.URL, "")
	lsn, err := c.GetLsnByTimestamp("tn1", "tl1", time.Date(2026, 5, 21, 14, 30, 0, 0, time.UTC))
	if err != nil {
		t.Fatal(err)
	}
	if lsn != "0/A1B2C3D4" {
		t.Errorf("LSN: got %q", lsn)
	}
	want := "/v1/tenant/tn1/timeline/tl1/get_lsn_by_timestamp?timestamp=2026-05-21T14%3A30%3A00Z"
	if capturedURL != want {
		t.Errorf("URL:\n  want: %s\n  got:  %s", want, capturedURL)
	}
}

func TestCreateBranch_PostsCorrectBody(t *testing.T) {
	var capturedBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			t.Errorf("method: %s", r.Method)
		}
		json.NewDecoder(r.Body).Decode(&capturedBody)
		w.Write([]byte(`{"timeline_id":"tl_new","last_record_lsn":"0/AB12"}`))
	}))
	defer srv.Close()

	c := New(srv.URL, "tok")
	resp, err := c.CreateBranch("tn1", CreateBranchReq{
		AncestorTimelineID: "tl_parent",
		AncestorStartLSN:   "0/AB12",
		NewTimelineID:      "tl_new",
	})
	if err != nil {
		t.Fatal(err)
	}
	if resp.TimelineID != "tl_new" {
		t.Errorf("timelineId: %s", resp.TimelineID)
	}
	if capturedBody["ancestor_timeline_id"] != "tl_parent" {
		t.Errorf("body keys: %+v", capturedBody)
	}
	if capturedBody["new_timeline_id"] != "tl_new" {
		t.Errorf("body keys: %+v", capturedBody)
	}
}

func TestDo_AddsBearerToken(t *testing.T) {
	var authHeader string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader = r.Header.Get("Authorization")
		w.Write([]byte(`{"lsn":"0/0"}`))
	}))
	defer srv.Close()

	c := New(srv.URL, "secret-token")
	_, _ = c.GetLsnByTimestamp("tn1", "tl1", time.Now())
	if !strings.HasPrefix(authHeader, "Bearer ") {
		t.Errorf("authHeader: %s", authHeader)
	}
}

func TestDo_ErrorsOn500(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(500)
		w.Write([]byte(`internal error`))
	}))
	defer srv.Close()

	c := New(srv.URL, "")
	_, err := c.GetLsnByTimestamp("tn1", "tl1", time.Now())
	if err == nil {
		t.Fatal("expected error")
	}
	if !strings.Contains(err.Error(), "500") {
		t.Errorf("error msg: %v", err)
	}
}
