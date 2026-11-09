{{/*
Stateless app: ConfigMap (env) + Deployment + Service, optional NetworkPolicy, PDB and HPA.
`networkPolicy.allowFrom` lists the `app` labels allowed in on the app port; unset = open (gateway, frontend).
Secrets are mounted as files from an existing Secret (created from a SealedSecret) and, for
Spring services, read through `spring.config.import=configtree:` so `${DB_PASSWORD}` etc. resolve.
*/}}
{{- define "common.app" -}}
{{- $name := include "common.name" . -}}
{{- $secretName := tpl (.Values.secret.name | default (printf "%s-secrets" $name)) . -}}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ $name }}-config
  labels:
    {{- include "common.labels" . | nindent 4 }}
data:
  {{- range $k, $v := .Values.env }}
  {{ $k }}: {{ tpl (toString $v) $ | quote }}
  {{- end }}
  {{- if .Values.javaOpts }}
  JAVA_OPTS: {{ .Values.javaOpts | quote }}
  {{- end }}
  {{- if and .Values.secret.enabled .Values.secret.springConfigTree }}
  SPRING_CONFIG_IMPORT: "optional:configtree:{{ .Values.secret.mountPath }}/"
  {{- end }}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
  labels:
    {{- include "common.labels" . | nindent 4 }}
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.replicaCount }}
  {{- end }}
  revisionHistoryLimit: 3
  selector:
    matchLabels:
      {{- include "common.selectorLabels" . | nindent 6 }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  template:
    metadata:
      labels:
        {{- include "common.labels" . | nindent 8 }}
      annotations:
        checksum/config: {{ toJson .Values.env | sha256sum | trunc 16 }}
        {{- with .Values.podAnnotations }}
        {{- tpl (toYaml .) $ | nindent 8 }}
        {{- end }}
    spec:
      automountServiceAccountToken: false
      terminationGracePeriodSeconds: {{ .Values.terminationGracePeriodSeconds | default 45 }}
      securityContext:
        {{- include "common.podSecurityContext" . | nindent 8 }}
      containers:
        - name: {{ $name }}
          image: {{ include "common.image" . }}
          imagePullPolicy: IfNotPresent
          {{- with .Values.args }}
          args:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          ports:
            - name: http
              containerPort: {{ .Values.port }}
            {{- with .Values.extraContainerPorts }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
          envFrom:
            - configMapRef:
                name: {{ $name }}-config
          {{- if ne .Values.preStopSleep false }}
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep 10"]
          {{- end }}
          resources:
            {{- include "common.resources" . | nindent 12 }}
          securityContext:
            {{- include "common.containerSecurityContext" . | nindent 12 }}
          livenessProbe:
            httpGet:
              path: {{ .Values.probes.basePath }}{{ .Values.probes.livePath }}
              port: {{ .Values.probes.port | default "http" }}
            periodSeconds: 15
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: {{ .Values.probes.basePath }}{{ .Values.probes.readyPath }}
              port: {{ .Values.probes.port | default "http" }}
            periodSeconds: 10
            failureThreshold: 3
          startupProbe:
            httpGet:
              path: {{ .Values.probes.basePath }}{{ .Values.probes.startupPath }}
              port: {{ .Values.probes.port | default "http" }}
            periodSeconds: 5
            failureThreshold: {{ .Values.probes.startupFailureThreshold | default 36 }}
          volumeMounts:
            - name: tmp
              mountPath: /tmp
            {{- if .Values.secret.enabled }}
            - name: secrets
              mountPath: {{ .Values.secret.mountPath }}
              readOnly: true
            {{- end }}
            {{- with .Values.extraVolumeMounts }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
      volumes:
        - name: tmp
          emptyDir: {}
        {{- if .Values.secret.enabled }}
        - name: secrets
          secret:
            secretName: {{ $secretName }}
        {{- end }}
        {{- with .Values.extraVolumes }}
        {{- tpl (toYaml .) $ | nindent 8 }}
        {{- end }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $name }}
  labels:
    {{- include "common.labels" . | nindent 4 }}
spec:
  type: ClusterIP
  selector:
    {{- include "common.selectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.port }}
      targetPort: http
    {{- with .Values.extraServicePorts }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
{{- with (.Values.networkPolicy | default dict).allowFrom }}
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ $name }}
  labels:
    {{- include "common.labels" $ | nindent 4 }}
spec:
  podSelector:
    matchLabels:
      {{- include "common.selectorLabels" $ | nindent 6 }}
  policyTypes: [Ingress]
  ingress:
    - from:
        {{- range . }}
        - podSelector:
            matchLabels:
              app: {{ . }}
        {{- end }}
      ports:
        - protocol: TCP
          port: {{ $.Values.port }}
{{- end }}
{{- if .Values.pdb.enabled }}
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ $name }}
  labels:
    {{- include "common.labels" . | nindent 4 }}
spec:
  minAvailable: {{ .Values.pdb.minAvailable }}
  selector:
    matchLabels:
      {{- include "common.selectorLabels" . | nindent 6 }}
{{- end }}
{{- if .Values.autoscaling.enabled }}
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ $name }}
  labels:
    {{- include "common.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ $name }}
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetCPUUtilizationPercentage }}
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetMemoryUtilizationPercentage }}
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
{{- end }}
{{- end -}}
