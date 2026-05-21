// Package obs provides an OBS client and manifest schema mirroring
// lakeon-api's com.lakeon.obs.ManifestObjects (T14). JSON tag names
// MUST stay in sync with the Java records so Go can read manifests
// written by Java and vice versa.
package obs

import "time"

// BranchEntry mirrors ManifestObjects.BranchEntry.
type BranchEntry struct {
	BranchID string `json:"branch_id"`
	Parent   string `json:"parent,omitempty"`
	LSN      string `json:"lsn,omitempty"`
}

// DatabaseEntry mirrors ManifestObjects.DatabaseEntry.
type DatabaseEntry struct {
	DBID         string        `json:"db_id"`
	NeonTenantID string        `json:"neon_tenant_id,omitempty"`
	Name         string        `json:"name"`
	TimelineID   string        `json:"timeline_id"`
	CreatedAt    time.Time     `json:"created_at"`
	DeletedAt    *time.Time    `json:"deleted_at,omitempty"`
	Branches     []BranchEntry `json:"branches,omitempty"`
}

// TenantManifest mirrors ManifestObjects.TenantManifest.
type TenantManifest struct {
	ManifestVersion int             `json:"manifest_version"`
	TenantID        string          `json:"tenant_id"`
	OwnerEmail      string          `json:"owner_email"`
	CreatedAt       time.Time       `json:"created_at"`
	UpdatedAt       time.Time       `json:"updated_at"`
	Version         int64           `json:"version"`
	Databases       []DatabaseEntry `json:"databases,omitempty"`
}

// OwnersIndex mirrors ManifestObjects.OwnersIndex.
type OwnersIndex struct {
	IndexVersion int                 `json:"index_version"`
	UpdatedAt    time.Time           `json:"updated_at"`
	Owners       map[string][]string `json:"owners"`
}
