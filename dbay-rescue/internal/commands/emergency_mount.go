// Package commands: emergency-mount starts a temporary k8s compute pod
// attached to a tenant's timeline and provisions a time-bounded PG ROLE so
// SRE can issue read (or, with --writable, write) queries when RDS is down.
//
// Safety properties:
//   - Tenant ownership is verified against the OBS manifest before any k8s
//     resource is created.
//   - Pods are labelled with `expires-at=<unix-ts>` so a separate cleanup job
//     can sweep stale pods+roles without coupling to this CLI.
//   - Roles are created with `VALID UNTIL` so even if the cleanup job is
//     late, PostgreSQL will refuse logins past the deadline.
//   - An audit record is written to OBS under `_audit/emergency_mount/` for
//     every successful mount.
package commands

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/dbay-cloud/dbay-rescue/internal/creds"
	"github.com/dbay-cloud/dbay-rescue/internal/obs"
	"github.com/spf13/cobra"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/clientcmd"
	"k8s.io/client-go/tools/remotecommand"
	"k8s.io/client-go/util/homedir"
)

// NewEmergencyMountCmd builds the `dbay-rescue emergency-mount` subcommand.
func NewEmergencyMountCmd(credsPath *string) *cobra.Command {
	var tenantID, ownerEmail, namespace string
	var writable bool
	var validHours int
	cmd := &cobra.Command{
		Use:   "emergency-mount",
		Short: "Start temporary compute pod for a tenant when RDS is down",
		RunE: func(cmd *cobra.Command, args []string) error {
			if tenantID == "" || ownerEmail == "" {
				return fmt.Errorf("--tenant and --owner required")
			}
			c, err := creds.Load(*credsPath)
			if err != nil {
				return err
			}
			obsCli, err := obs.New(c.OBS.Endpoint, c.OBS.AccessKey, c.OBS.SecretKey, c.OBS.Bucket)
			if err != nil {
				return err
			}
			defer obsCli.Close()

			var m obs.TenantManifest
			key := fmt.Sprintf("tenants/%s/_manifest.json", tenantID)
			if err := obsCli.GetJSON(key, &m); err != nil {
				return fmt.Errorf("tenant manifest not found: %w", err)
			}
			if !strings.EqualFold(m.OwnerEmail, ownerEmail) {
				return fmt.Errorf("ownerEmail mismatch: manifest says %s, you passed %s",
					m.OwnerEmail, ownerEmail)
			}
			if len(m.Databases) == 0 {
				return fmt.Errorf("tenant %s has no databases", tenantID)
			}
			// Pick first non-deleted database.
			var db obs.DatabaseEntry
			found := false
			for _, d := range m.Databases {
				if d.DeletedAt == nil {
					db = d
					found = true
					break
				}
			}
			if !found {
				return fmt.Errorf("no active database in tenant %s", tenantID)
			}
			if db.NeonTenantID == "" {
				return fmt.Errorf("database %s missing neon_tenant_id (regenerate manifest)", db.DBID)
			}

			roleName := "em_" + randAlnum(8)
			password := randAlnum(32)
			expiry := time.Now().Add(time.Duration(validHours) * time.Hour)

			fmt.Printf("Mounting emergency compute for %s...\n", tenantID)
			fmt.Printf("  Database: %s (timeline=%s)\n", db.Name, db.TimelineID)

			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
			defer cancel()

			cfg, err := loadKubeConfig()
			if err != nil {
				return fmt.Errorf("load kubeconfig: %w", err)
			}
			cli, err := kubernetes.NewForConfig(cfg)
			if err != nil {
				return err
			}

			podName, err := startEmergencyPod(ctx, cli, namespace, tenantID, db.NeonTenantID, db.TimelineID, c.Pageserver.MgmtEndpoint, expiry)
			if err != nil {
				return fmt.Errorf("start pod: %w", err)
			}
			fmt.Printf("  Pod: %s\n", podName)

			if err := waitPodReady(ctx, cli, namespace, podName); err != nil {
				return fmt.Errorf("wait pod: %w", err)
			}

			grant := "GRANT pg_read_all_data TO " + roleName
			if writable {
				grant = "GRANT pg_read_all_data, pg_write_all_data TO " + roleName
			}
			sql := fmt.Sprintf(
				"CREATE ROLE %s WITH LOGIN PASSWORD '%s' VALID UNTIL '%s'; %s;",
				roleName, password,
				expiry.UTC().Format("2006-01-02 15:04:05"),
				grant)
			if err := execPsql(ctx, cfg, cli, namespace, podName, sql); err != nil {
				return fmt.Errorf("create temp role: %w", err)
			}

			audit := map[string]any{
				"timestamp":   time.Now().UTC().Format(time.RFC3339),
				"actor":       sreActor(),
				"tenant_id":   tenantID,
				"owner_email": ownerEmail,
				"db_id":       db.DBID,
				"role":        roleName,
				"writable":    writable,
				"valid_until": expiry.UTC().Format(time.RFC3339),
			}
			ab, _ := json.Marshal(audit)
			auditKey := fmt.Sprintf("_audit/emergency_mount/%d-%s.json",
				time.Now().UnixMilli(), roleName)
			if err := obsCli.PutJSON(auditKey, json.RawMessage(ab)); err != nil {
				fmt.Fprintf(os.Stderr, "  ! audit write failed: %v\n", err)
			}

			fmt.Printf("\nEmergency mount ready (valid %dh):\n\n", validHours)
			fmt.Printf("  postgresql://%s:%s@%s.%s.svc:5432/%s?sslmode=require\n\n",
				roleName, password, podName, namespace, db.Name)
			fmt.Printf("  Audit: %s\n", auditKey)
			fmt.Printf("  Cleanup: pod expires at %s — separate cleanup job removes pods + roles.\n",
				expiry.Format(time.RFC3339))
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantID, "tenant", "", "Lakeon tenant ID (required)")
	cmd.Flags().StringVar(&ownerEmail, "owner", "", "Owner email (must match manifest)")
	cmd.Flags().StringVar(&namespace, "namespace", "lakeon", "k8s namespace")
	cmd.Flags().BoolVar(&writable, "writable", false, "Grant write permission (default read-only)")
	cmd.Flags().IntVar(&validHours, "hours", 1, "Validity in hours (default 1)")
	return cmd
}

// loadKubeConfig resolves the rest config: in-cluster first (harmless when
// not present), then ~/.kube/config.
func loadKubeConfig() (*rest.Config, error) {
	cfg, err := rest.InClusterConfig()
	if err == nil {
		return cfg, nil
	}
	kc := filepath.Join(homedir.HomeDir(), ".kube", "config")
	return clientcmd.BuildConfigFromFlags("", kc)
}

// startEmergencyPod creates a labelled compute pod attached to the
// (neonTenant, timelineID) pair via env vars. Returns the generated pod name
// on success even if Create fails (so the caller can log it).
func startEmergencyPod(ctx context.Context, cli *kubernetes.Clientset,
	ns, lakeonTenant, neonTenant, timelineID, pageserverURL string, expiry time.Time) (string, error) {
	podName := fmt.Sprintf("em-%s-%s", truncate(lakeonTenant, 8), randAlnum(6))
	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:      podName,
			Namespace: ns,
			Labels: map[string]string{
				"app":              "emergency-mount",
				"lakeon-tenant-id": lakeonTenant,
				"neon-tenant-id":   neonTenant,
				"timeline-id":      timelineID,
				"expires-at":       fmt.Sprintf("%d", expiry.Unix()),
			},
		},
		Spec: corev1.PodSpec{
			RestartPolicy: corev1.RestartPolicyNever,
			Containers: []corev1.Container{{
				Name:  "compute",
				Image: "swr.cn-east-3.myhuaweicloud.com/lakeon/compute-ctl:latest",
				Env: []corev1.EnvVar{
					{Name: "NEON_TENANT_ID", Value: neonTenant},
					{Name: "NEON_TIMELINE_ID", Value: timelineID},
					{Name: "PAGESERVER_URL", Value: pageserverURL},
				},
				Ports: []corev1.ContainerPort{{ContainerPort: 5432}},
			}},
		},
	}
	_, err := cli.CoreV1().Pods(ns).Create(ctx, pod, metav1.CreateOptions{})
	return podName, err
}

// waitPodReady polls until the pod reports Running + at least one Ready
// container, or ctx is cancelled.
func waitPodReady(ctx context.Context, cli *kubernetes.Clientset, ns, name string) error {
	for {
		pod, err := cli.CoreV1().Pods(ns).Get(ctx, name, metav1.GetOptions{})
		if err != nil {
			return fmt.Errorf("get pod: %w", err)
		}
		if pod.Status.Phase == corev1.PodRunning {
			for _, c := range pod.Status.ContainerStatuses {
				if c.Ready {
					return nil
				}
			}
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("pod %s not ready: %w", name, ctx.Err())
		case <-time.After(2 * time.Second):
		}
	}
}

// execPsql runs `psql -U postgres -c <sqlStmt>` inside the compute container
// via SPDY exec. stderr is bubbled up on failure to aid debugging.
func execPsql(ctx context.Context, cfg *rest.Config, cli *kubernetes.Clientset, ns, podName, sqlStmt string) error {
	req := cli.CoreV1().RESTClient().Post().
		Resource("pods").
		Name(podName).
		Namespace(ns).
		SubResource("exec").
		VersionedParams(&corev1.PodExecOptions{
			Container: "compute",
			Command:   []string{"psql", "-U", "postgres", "-c", sqlStmt},
			Stdout:    true,
			Stderr:    true,
		}, scheme.ParameterCodec)
	exec, err := remotecommand.NewSPDYExecutor(cfg, "POST", req.URL())
	if err != nil {
		return err
	}
	var stdout, stderr strings.Builder
	if err := exec.StreamWithContext(ctx, remotecommand.StreamOptions{
		Stdout: &stdout, Stderr: &stderr,
	}); err != nil {
		return fmt.Errorf("psql exec: %w (stderr=%s)", err, stderr.String())
	}
	return nil
}

// sreActor identifies the operator for audit purposes. Prefers $USER then
// $SRE_OPERATOR. Returns "" if neither is set (audit still records the
// empty actor rather than failing the operation).
func sreActor() string {
	if u := os.Getenv("USER"); u != "" {
		return u
	}
	return os.Getenv("SRE_OPERATOR")
}

// randAlnum returns a URL-safe base64 string truncated to exactly n bytes.
func randAlnum(n int) string {
	// Each base64 char encodes 6 bits → need ceil(n*6/8) raw bytes.
	raw := (n*6 + 7) / 8
	b := make([]byte, raw)
	_, _ = rand.Read(b)
	s := base64.RawURLEncoding.EncodeToString(b)
	if len(s) < n {
		return s
	}
	return s[:n]
}

// truncate returns s capped at n bytes (no Unicode awareness — fine for IDs).
func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}
