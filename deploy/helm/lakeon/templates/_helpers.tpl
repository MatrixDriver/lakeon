{{/*
LakeOn Helm Chart 通用辅助模板
*/}}

{{/*
Chart 名称
*/}}
{{- define "lakeon.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
完整名称 (release + chart)
*/}}
{{- define "lakeon.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
通用标签
*/}}
{{- define "lakeon.labels" -}}
helm.sh/chart: {{ include "lakeon.name" . }}-{{ .Chart.Version | replace "+" "_" }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}

{{/*
控制面资源开关。默认 all 保持历史单集群部署。
*/}}
{{- define "lakeon.controlPlaneEnabled" -}}
{{- $mode := .Values.plane.mode | default "all" -}}
{{- if or (eq $mode "all") (eq $mode "control") -}}true{{- end -}}
{{- end }}

{{/*
数据面资源开关。默认 all 保持历史单集群部署。
*/}}
{{- define "lakeon.dataPlaneEnabled" -}}
{{- $mode := .Values.plane.mode | default "all" -}}
{{- if or (eq $mode "all") (eq $mode "data") -}}true{{- end -}}
{{- end }}

{{/*
Safekeeper 连接串列表，用于 compute 配置
*/}}
{{- define "lakeon.safekeeperConnstrings" -}}
{{- $ns := .Values.global.namespace -}}
{{- $port := .Values.safekeeper.pgPort | int -}}
{{- $replicas := .Values.safekeeper.replicas | int -}}
{{- $result := list -}}
{{- range $i := until $replicas -}}
{{- $result = append $result (printf "safekeeper-%d.safekeeper.%s.svc.cluster.local:%d" $i $ns $port) -}}
{{- end -}}
{{- join "," $result -}}
{{- end }}

{{/*
Pageserver stable node descriptors for lakeon-api placement.
Format: id=httpUrl|pgHost|pgPort,id=...
*/}}
{{- define "lakeon.pageserverNodesRaw" -}}
{{- if .Values.dataPlane.pageserverNodesRawOverride -}}
{{- .Values.dataPlane.pageserverNodesRawOverride -}}
{{- else -}}
{{- $ns := .Values.dataPlane.namespace | default .Values.global.namespace -}}
{{- $httpPort := .Values.pageserver.httpPort | int -}}
{{- $pgPort := .Values.pageserver.pgPort | int -}}
{{- $replicas := .Values.dataPlane.pageserverReplicas | default .Values.pageserver.replicas | int -}}
{{- $result := list -}}
{{- range $i := until $replicas -}}
{{- $host := printf "pageserver-%d.pageserver-headless.%s.svc.cluster.local" $i $ns -}}
{{- $result = append $result (printf "ps-%d=http://%s:%d|%s|%d" $i $host $httpPort $host $pgPort) -}}
{{- end -}}
{{- join "," $result -}}
{{- end }}
{{- end }}
