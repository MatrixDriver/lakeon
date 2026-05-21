package neon

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

type Client struct {
	base   string
	token  string
	client *http.Client
}

func New(base, token string) *Client {
	return &Client{
		base:   base,
		token:  token,
		client: &http.Client{Timeout: 30 * time.Second},
	}
}

type LsnByTimestampResp struct {
	LSN string `json:"lsn"`
}

func (c *Client) GetLsnByTimestamp(tenantID, timelineID string, ts time.Time) (string, error) {
	u := fmt.Sprintf("%s/v1/tenant/%s/timeline/%s/get_lsn_by_timestamp?timestamp=%s",
		c.base, tenantID, timelineID, url.QueryEscape(ts.UTC().Format(time.RFC3339)))
	var out LsnByTimestampResp
	if err := c.do(http.MethodGet, u, nil, &out); err != nil {
		return "", err
	}
	return out.LSN, nil
}

type CreateBranchReq struct {
	AncestorTimelineID string `json:"ancestor_timeline_id"`
	AncestorStartLSN   string `json:"ancestor_start_lsn"`
	NewTimelineID      string `json:"new_timeline_id"`
}

type CreateBranchResp struct {
	TimelineID    string `json:"timeline_id"`
	LastRecordLSN string `json:"last_record_lsn"`
}

func (c *Client) CreateBranch(tenantID string, req CreateBranchReq) (*CreateBranchResp, error) {
	u := fmt.Sprintf("%s/v1/tenant/%s/timeline", c.base, tenantID)
	var out CreateBranchResp
	if err := c.do(http.MethodPost, u, req, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

type TimelineInfo struct {
	TimelineID        string `json:"timeline_id"`
	LastRecordLSN     string `json:"last_record_lsn"`
	DiskConsistentLSN string `json:"disk_consistent_lsn"`
	LatestGcCutoffLSN string `json:"latest_gc_cutoff_lsn"`
}

func (c *Client) GetTimelineInfo(tenantID, timelineID string) (*TimelineInfo, error) {
	u := fmt.Sprintf("%s/v1/tenant/%s/timeline/%s", c.base, tenantID, timelineID)
	var out TimelineInfo
	if err := c.do(http.MethodGet, u, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) do(method, url string, body, out any) error {
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, url, reader)
	if err != nil {
		return err
	}
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	rb, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		return fmt.Errorf("pageserver %s %s → %d: %s", method, url, resp.StatusCode, string(rb))
	}
	if out != nil {
		return json.Unmarshal(rb, out)
	}
	return nil
}
