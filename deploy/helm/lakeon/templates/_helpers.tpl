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
