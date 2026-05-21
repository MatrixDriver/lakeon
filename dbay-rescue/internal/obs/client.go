package obs

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"

	hwobs "github.com/huaweicloud/huaweicloud-sdk-go-obs/obs"
)

// Client is a thin wrapper around the Huawei Cloud OBS Go SDK that
// reads/writes JSON manifests under a single bucket.
type Client struct {
	c      *hwobs.ObsClient
	bucket string
}

// New constructs a Client. endpoint/ak/sk/bucket come from creds.Loader.
func New(endpoint, ak, sk, bucket string) (*Client, error) {
	c, err := hwobs.New(ak, sk, endpoint)
	if err != nil {
		return nil, fmt.Errorf("obs new: %w", err)
	}
	return &Client{c: c, bucket: bucket}, nil
}

// Close releases SDK resources.
func (c *Client) Close() {
	if c != nil && c.c != nil {
		c.c.Close()
	}
}

// Bucket returns the bucket name this client was initialized with.
func (c *Client) Bucket() string { return c.bucket }

// GetJSON reads object `key` and decodes JSON into `out`.
func (c *Client) GetJSON(key string, out any) error {
	resp, err := c.c.GetObject(&hwobs.GetObjectInput{
		GetObjectMetadataInput: hwobs.GetObjectMetadataInput{
			Bucket: c.bucket,
			Key:    key,
		},
	})
	if err != nil {
		return fmt.Errorf("get %s: %w", key, err)
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("read %s: %w", key, err)
	}
	if err := json.Unmarshal(b, out); err != nil {
		return fmt.Errorf("decode %s: %w", key, err)
	}
	return nil
}

// PutJSON writes JSON-encoded `v` to `key` as application/json.
func (c *Client) PutJSON(key string, v any) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return fmt.Errorf("encode %s: %w", key, err)
	}
	_, err = c.c.PutObject(&hwobs.PutObjectInput{
		PutObjectBasicInput: hwobs.PutObjectBasicInput{
			ObjectOperationInput: hwobs.ObjectOperationInput{
				Bucket: c.bucket,
				Key:    key,
			},
			HttpHeader: hwobs.HttpHeader{
				ContentType: "application/json",
			},
		},
		Body: bytes.NewReader(b),
	})
	if err != nil {
		return fmt.Errorf("put %s: %w", key, err)
	}
	return nil
}

// DeleteKey removes the object at `key`. Missing objects are treated as success.
func (c *Client) DeleteKey(key string) error {
	_, err := c.c.DeleteObject(&hwobs.DeleteObjectInput{Bucket: c.bucket, Key: key})
	if err != nil {
		if IsNotFound(err) {
			return nil
		}
		return fmt.Errorf("delete %s: %w", key, err)
	}
	return nil
}

// ListKeys returns object keys under `prefix`, paginating internally.
func (c *Client) ListKeys(prefix string) ([]string, error) {
	var keys []string
	marker := ""
	for {
		out, err := c.c.ListObjects(&hwobs.ListObjectsInput{
			Bucket: c.bucket,
			Marker: marker,
			ListObjsInput: hwobs.ListObjsInput{
				Prefix:  prefix,
				MaxKeys: 1000,
			},
		})
		if err != nil {
			return nil, fmt.Errorf("list %s: %w", prefix, err)
		}
		for _, o := range out.Contents {
			keys = append(keys, o.Key)
		}
		if !out.IsTruncated {
			break
		}
		// Prefer the server-supplied NextMarker; fall back to the last key.
		if out.NextMarker != "" {
			marker = out.NextMarker
		} else if n := len(out.Contents); n > 0 {
			marker = out.Contents[n-1].Key
		} else {
			break
		}
	}
	return keys, nil
}

// IsNotFound reports whether err indicates a missing OBS object (HTTP 404 / NoSuchKey).
func IsNotFound(err error) bool {
	if err == nil {
		return false
	}
	var obsErr hwobs.ObsError
	if errors.As(err, &obsErr) {
		if strings.HasPrefix(obsErr.Status, "404") {
			return true
		}
		switch obsErr.Code {
		case "NoSuchKey", "NoSuchBucket", "NotFound":
			return true
		}
	}
	// Fallback string match in case the SDK ever wraps differently.
	msg := err.Error()
	return strings.Contains(msg, "Status=404") ||
		strings.Contains(msg, "NoSuchKey") ||
		strings.Contains(msg, "404 Not Found")
}
