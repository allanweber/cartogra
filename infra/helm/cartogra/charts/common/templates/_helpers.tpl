{{- define "common.name" -}}
{{- .Values.nameOverride | default .Chart.Name -}}
{{- end -}}

{{- define "common.version" -}}
{{- .Values.image.tag | default .Values.global.imageTag | default .Chart.AppVersion -}}
{{- end -}}

{{/* image.registry wins; empty falls back to global.imageRegistry (our own images). */}}
{{- define "common.image" -}}
{{- $registry := .Values.image.registry | default .Values.global.imageRegistry -}}
{{- $tag := include "common.version" . -}}
{{- if eq $tag "latest" -}}{{- fail (printf "%s: image tag `latest` is not allowed" (include "common.name" .)) -}}{{- end -}}
{{- if $registry -}}
{{- printf "%s/%s:%s" $registry .Values.image.repository $tag -}}
{{- else -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}
{{- end -}}

{{- define "common.selectorLabels" -}}
app: {{ include "common.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "common.labels" -}}
{{ include "common.selectorLabels" . }}
version: {{ include "common.version" . | quote }}
environment: {{ required "global.environment is required" .Values.global.environment }}
team: {{ .Values.team | default .Values.global.team }}
app.kubernetes.io/name: {{ include "common.name" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: cartogra
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "common.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: {{ .Values.podSecurityContext.runAsUser | default 1000 }}
runAsGroup: {{ .Values.podSecurityContext.runAsGroup | default 1000 }}
fsGroup: {{ .Values.podSecurityContext.fsGroup | default 1000 }}
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "common.containerSecurityContext" -}}
readOnlyRootFilesystem: true
allowPrivilegeEscalation: false
capabilities:
  drop: [ALL]
{{- end -}}

{{- define "common.resources" -}}
{{- $r := required (printf "%s: resources are required" (include "common.name" .)) .Values.resources -}}
{{- $_ := required (printf "%s: resources.requests is required" (include "common.name" .)) $r.requests -}}
{{- $_ := required (printf "%s: resources.limits is required" (include "common.name" .)) $r.limits -}}
{{ toYaml $r }}
{{- end -}}
