{{- define "acme.data" -}}
{{- .Files.Get "files/services.json" | fromJson | toJson -}}
{{- end -}}

{{- define "acme.image" -}}
{{- $tag := required "image.tag is required" .Values.image.tag -}}
{{- if eq $tag "latest" -}}{{- fail "image tag `latest` is not allowed" -}}{{- end -}}
{{- if .Values.image.registry -}}
{{- printf "%s/%s:%s" .Values.image.registry .Values.image.repository $tag -}}
{{- else -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}
{{- end -}}

{{- define "acme.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: 1000
runAsGroup: 1000
fsGroup: 1000
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "acme.containerSecurityContext" -}}
readOnlyRootFilesystem: true
allowPrivilegeEscalation: false
capabilities:
  drop: [ALL]
{{- end -}}
