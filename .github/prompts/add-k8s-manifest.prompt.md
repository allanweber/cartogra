---
mode: 'agent'
description: 'Scaffold Kubernetes manifests (Deployment, Service, HPA, PDB, ConfigMap) for a Cartogra service'
---

You are working in the Cartogra monorepo. The full project rules are in `.github/copilot-instructions.md` — apply them to everything you generate.

Scaffold Kubernetes manifests (Deployment, Service, HPA, PDB, ConfigMap) for a Cartogra service.

**Usage:** provide `<service> [namespace]` — namespace defaults to `dev` (e.g., `registry prod`)

## Steps

1. **Parse arguments**: service name, namespace (default: `dev`)
2. **Create files** under `infra/k8s/<service>/`

### `deployment.yaml`
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: <service>
  namespace: <namespace>
  labels:
    app: <service>
    version: v1.0.0
    environment: <namespace>
    team: platform
spec:
  replicas: 2
  selector:
    matchLabels:
      app: <service>
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  template:
    metadata:
      labels:
        app: <service>
        version: v1.0.0
        environment: <namespace>
        team: platform
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
      containers:
        - name: <service>
          image: ghcr.io/cartogra/<service>:v1.0.0   # NEVER latest
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: <service>-config
          env:
            - name: JAVA_OPTS
              value: "-XX:MaxRAMPercentage=75"
          resources:
            requests:
              cpu: "250m"
              memory: "256Mi"
            limits:
              cpu: "500m"
              memory: "256Mi"   # memory limit = memory request (Guaranteed QoS)
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          volumeMounts:
            - name: tmp
              mountPath: /tmp
          startupProbe:
            httpGet:
              path: /actuator/health/live
              port: 8080
            failureThreshold: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/live
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 10
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/ready
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 3
      volumes:
        - name: tmp
          emptyDir: {}
```

### `service.yaml`
```yaml
apiVersion: v1
kind: Service
metadata:
  name: <service>
  namespace: <namespace>
  labels:
    app: <service>
spec:
  type: ClusterIP   # NEVER LoadBalancer for internal services
  selector:
    app: <service>
  ports:
    - port: 80
      targetPort: 8080
      protocol: TCP
```

### `hpa.yaml`
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: <service>
  namespace: <namespace>
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: <service>
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
```

### `pdb.yaml`
```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: <service>
  namespace: <namespace>
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: <service>
```

### `configmap.yaml`
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: <service>-config
  namespace: <namespace>
data:
  SPRING_PROFILES_ACTIVE: <namespace>
  SERVER_PORT: "8080"
```

## Verify rules checklist
- [ ] `requests` AND `limits` on every container
- [ ] `memory limit == memory request` (Guaranteed QoS)
- [ ] All 3 probes present with correct paths
- [ ] Full security context: `runAsNonRoot`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`
- [ ] `/tmp` mounted as `emptyDir`
- [ ] Image tag is semantic version — NEVER `latest`
- [ ] Service type `ClusterIP`
- [ ] PDB `minAvailable ≥ 2`
- [ ] HPA `minReplicas ≥ PDB.minAvailable`; scaleDown stabilization 300s
- [ ] Rolling update: `maxUnavailable: 0, maxSurge: 1`
- [ ] Labels: `app`, `version`, `environment`, `team` on all resources
