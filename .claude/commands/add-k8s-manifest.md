You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Scaffold Kubernetes manifests (Deployment, Service, HPA, PDB, ConfigMap) for a Cartogra service.

Arguments: $ARGUMENTS
(Expected: `<service> [namespace]` — e.g., `/add-k8s-manifest registry prod`)
(namespace defaults to `dev` if omitted)

## Steps

1. **Parse arguments**: service name, namespace (default: `dev`)

2. **Create file** at `k8s/<service>/deployment.yaml`

3. **Deployment template**:
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

4. **Service template** (`k8s/<service>/service.yaml`):
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

5. **HPA template** (`k8s/<service>/hpa.yaml`):
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
         stabilizationWindowSeconds: 300   # prevent flapping
   ```

6. **PDB template** (`k8s/<service>/pdb.yaml`):
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

7. **ConfigMap template** (`k8s/<service>/configmap.yaml`):
   ```yaml
   apiVersion: v1
   kind: ConfigMap
   metadata:
     name: <service>-config
     namespace: <namespace>
   data:
     SPRING_PROFILES_ACTIVE: <namespace>
     SERVER_PORT: "8080"
     # Add service-specific non-secret config here
   ```

8. **Verify rules checklist:**
   - [ ] `requests` AND `limits` on every container
   - [ ] `memory limit == memory request` (Guaranteed QoS)
   - [ ] All 3 probes: `startupProbe`, `livenessProbe`, `readinessProbe`
   - [ ] Probe paths: `/actuator/health/live` + `/actuator/health/ready`
   - [ ] `securityContext`: `runAsNonRoot`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`
   - [ ] `/tmp` mounted as `emptyDir` (required when `readOnlyRootFilesystem: true`)
   - [ ] Image tag is semantic version (`v1.x.x`) — NEVER `latest`
   - [ ] Service type is `ClusterIP` — NOT `LoadBalancer`
   - [ ] PDB `minAvailable ≥ 2`
   - [ ] HPA `minReplicas ≥ PDB.minAvailable`; scaleDown stabilization 300s
   - [ ] Rolling update: `maxUnavailable: 0, maxSurge: 1`
   - [ ] Labels: `app`, `version`, `environment`, `team` on all resources
   - [ ] Secrets mounted as volumes — NOT as env vars in manifest
