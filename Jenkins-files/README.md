# CI/CD using Jenkins and ArgoCD
A project to deploy the simple Flask application to a Kubernetes cluster using ArgoCD and Jenkins.

---

## Part 2: Deploy Jenkins in Kubernetes

I will be following the guide for installing in Kubernetes as can be found here: https://www.jenkins.io/doc/book/installing/kubernetes/

There are 5 steps to get Jenkins up and running in Kubernetes:

1. Create a Namespace
2. Create a service account
3. Create local persistant volume
4. Create the Deployment of the app
5. Create the service for Jenkins

---

### Create a Namespace

In the cluster run the following `kubectl` command to create the namespace for Jenkins:
```
kubectl create namespace jenkins-namespace
```

### Create the Jenkins Service Account

The service account will require a CLusterRole to be applied to it to allow it to manage the cluster components:

```
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: jenkins-admin
rules:
  - apiGroups: [""]
    resources: ["*"]
    verbs: ["*"]
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jenkins-admin
  namespace: jenkins-namespace
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: jenkins-admin
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: jenkins-admin
subjects:
- kind: ServiceAccount
  name: jenkins-admin
  namespace: jenkins-namespace
```

save this to `jenkins-service-account.yaml` then run `kubectl apply -f jenkins-service-account.yaml` to create the SA, clusterrole and the binding.

### Create Local Persistent Volume

For data to persist a volume will need to be created from the Node hosting Jenkins and a PCV will need to be created to use a piece of that volume:

```
kind: StorageClass
apiVersion: storage.k8s.io/v1
metadata:
  name: local-storage
provisioner: kubernetes.io/no-provisioner
volumeBindingMode: WaitForFirstConsumer
---
apiVersion: v1
kind: PersistentVolume
metadata:
  name: jenkins-pv-volume
  labels:
    type: local
spec:
  storageClassName: local-storage
  claimRef:
    name: jenkins-pv-claim
    namespace: jenkins-namespace
  capacity:
    storage: 5Gi
  accessModes:
    - ReadWriteOnce
  local:
    path: /mnt
  nodeAffinity:
    required:
      nodeSelectorTerms:
      - matchExpressions:
        - key: kubernetes.io/hostname
          operator: In
          values:
          - workerserver
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: jenkins-pv-claim
  namespace: jenkins-namespace
spec:
  storageClassName: local-storage
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 3Gi
```

Save as `jenkins-pv-pvc.yaml`, then run `kubectl apply -f jenkins-pv-pcv.yaml` to create.

### Create the Deployment

