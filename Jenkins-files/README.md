# CI/CD using Jenkins and ArgoCD
A project to deploy Jenkins to Kubernetes and create ephemeral Pods to be used as worker nodes.

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

---

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

---

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

---

### Create the Deployment

Create `jenkins-deployment.yaml` and add the following:

```
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jenkins
  namespace: jenkins-namespace
spec:
  replicas: 1
  selector:
    matchLabels:
      app: jenkins-server
  strategy:
    type: Recreate
  template:
    metadata:
      labels:
        app: jenkins-server
    spec:
      securityContext:
            # Note: fsGroup may be customized for a bit of better
            # filesystem security on the shared host
            fsGroup: 1000
            runAsUser: 1000
            ### runAsGroup: 1000
      serviceAccountName: jenkins-admin
      containers:
        - name: jenkins
          image: jenkins/jenkins:lts
          # OPTIONAL: check for new floating-tag LTS releases whenever the pod is restarted:
          imagePullPolicy: Always
          resources:
            limits:
              memory: "2Gi"
              cpu: "1000m"
            requests:
              memory: "500Mi"
              cpu: "500m"
          ports:
            - name: httpport
              containerPort: 8080
            - name: jnlpport
              containerPort: 50000
          livenessProbe:
            httpGet:
              path: "/login"
              port: 8080
            initialDelaySeconds: 90
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 5
          readinessProbe:
            httpGet:
              path: "/login"
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
          volumeMounts:
            - name: jenkins-data
              mountPath: /var/jenkins_home
      volumes:
        - name: jenkins-data
          persistentVolumeClaim:
              claimName: jenkins-pv-claim
```

run `kubectl apply -f jenkins-deployment.yaml` to deploy.

The deployment will cretae a single replica of the Jenkins app to `jenkins-namespace`. The latest image will be pulled and used in the deployment, providing up to 2GB of Memory and 1 Core. It will also include both a `liveness probe` and a `readiness probe`.

If the `readiness probe` reports that the container is unable to recieve traffic on the endpoint "/login" Kubernetes will remove the pod fro the Service endpoints.

If the `liveness probe` is not reporting that the container is healthy Kubernetes will kill it and restart.

The deployment will also make use of the PVC created earlier.

As an extra step to ensure that the Local Persitent Volume is prepared to be accessed by Jenkins I ran the following (the runAsUser and fsGroup are both specified in the deployment yaml):

This should be run on the Node hosting the Persistent Volume created earlier (in my case "workerserver")

```
runAsUser=1000
fsGroup=1000   # Or custom ID, per above
mkdir -p /var/jenkins_home
chown -R $runAsUser:$fsGroup /var/jenkins_home
chmod -R g+rwX /var/jenkins_home
```

---

### Create the Service for jenkins

To make the deployment accessible a Service will need to be created:

```
apiVersion: v1
kind: Service
metadata:
  name: jenkins-service
  namespace: jenkins-namespace
  annotations:
      prometheus.io/scrape: 'true'
      prometheus.io/path:   /
      prometheus.io/port:   '8080'
spec:
  selector:
    app: jenkins-server
  type: NodePort
  ports:
    - port: 8080
      targetPort: 8080
      nodePort: 32000
```

This `NodePort` service will allow me to access the `jenkins-server` using port 32000 and the hosting node's IP address: 

`http://<node-ip-address>:32000`

To access the Jenkins server the initial password will need to be retrieved. This can be found by checking the Jenkins logs:

`kubectl logs <jenkins-pod-name> -n jenkins-namespace`

The password will be present in the logs. Simply copy and paste it over to the web console to start.

Now all that is left is to set up the first user. You do not need to do this as it also suggests ignoring this step and continuing as admin so this is up to you.

### Now that both ArgoCD and Jenkins are running on the Kubernetes cluster the next step will be to configure Jenkins and set up the first Pipeline.

---

## Part 3: Set up Jenkins to create worker pods in Kubernetes

---

### Create the Cloud in Jenkins

I have chosen to use worker pods from inside of the cluster which Jenkins is hosted to create Docker images to push to Docker Hub. To create the images I will be using [Kaniko](https://github.com/GoogleContainerTools/kaniko).

In the Jenkins master GUI the Kubernetes plugin is required to allow it to interact with the cluster. As Jenkins is using a Service Account that has been granted a Cluster Role which allows it to create Pods in all namespaces this will mean it has the right permissions from the start.

`Jenkins > Manage Jenkins > System Configuration > Plugins > "Kubernetes plugin"`

Once installed the next step is to set up Clouds

`Jenkins > Manage Jenkins > Clouds > New cloud`

I set it up as the following:
```
Name: Kubernetes
Kubernetes URL: https://kubernetes.default.svc # Left this as is due to Jenkins running in the cluster it will create Pods in
Kubernetes Names: jenkins-namespace
```

Then clicked "Test Connection". Connection confirmed on the return of "Connected to Kubernetes v1.33.4".

After this I went on to make sure that the inbound port for agents to connect to the master was specified:

`Manage Jenkins > Security > Agents > "TCP port for inbound agents"`
Here I selected "Fixed" and chose port 50000

---

### Create the Pod template:

`Clouds > Kubernetes > New pod template`

I decided on the following settings to get this set up as simplay as possible:

```
Name: Kaniko-Agent
Namespace: jenkins-namespace
Labels: kaniko
Usage: Only build jobs with label expressions matching this node
```

The next section is to build the Container Templates for JNLP (Java Network Launch Protocol) and Kaniko:

```
Name: jnlp
Docker image: jenkins/inbound-agent:latest
Working directory: /home/jenkins/agent

Name: kaniko
Docker image: gcr.io/kaniko-project/executor:latest
Working directory: /home/jenkins/agent
Command to run: N/A
Arguments to pass to the command: N/A
```

To pass the Secret created in the previous step a Volume will be needed:

```
Secret name: kaniko-secret
Mount path: /kaniko/.docker
```

With the agents set up to be built when a Pipeline job was run it is time to run the test:

---

### Create internal service for agents to connect

One issue during testing was that the JNLP was unable to reach the Jenkins master inetrnally. The following service was created specifically for internal communication inthe cluster:

```
apiVersion: v1
kind: Service
metadata:
  name: jenkins-internal
  namespace: jenkins-namespace
spec:
  type: ClusterIP
  selector:
    app: jenkins-server
  ports:
    - name: http
      port: 8080
      targetPort: 8080
    - name: jnlp
      port: 50000
      targetPort: 50000
```

I also made sure the port used by agents remained the same:

`Manage Jenkins > Security > Security`
Agents:
- TCP port for inbound agents
- Fixed: 50000

---

### Run the first test Pipeline:

`Create a Job > Name: "Test1" > Pipeline`

I left all fields blank for now and went straight into the Pipeline section:

```
Definition: "Pipeline script"
Script:

pipeline {
  agent {
    kubernetes {
      yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: worker
    image: alpine:3.19
    command:
      - cat
    tty: true
"""
    }
  }

  stages {
    stage('Say Hello') {
      steps {
        container('worker') {
          sh 'echo "Hello World"'
        }
      }
    }
  }
}
```

Apply and Save

To see the pods being created in realtime i ran `kubectl get pods -n jenkins-workspace -w` to monitor their creation as the Pipeline was run.
Here is the output:

```
Running on test1-1-13rnp-zkrtr-jddf8 in /home/jenkins/agent/workspace/Test1
[Pipeline] {
[Pipeline] stage
[Pipeline] { (Say Hello)
[Pipeline] container
[Pipeline] {
[Pipeline] sh
+ echo 'Hello World'
Hello World
[Pipeline] }
[Pipeline] // container
[Pipeline] }
[Pipeline] // stage
[Pipeline] }
[Pipeline] // node
[Pipeline] }
[Pipeline] // podTemplate
[Pipeline] End of Pipeline
Finished: SUCCESS
```

Success, Jenkins created a worker agent using an ephemeral container. Once the job was run the pod was removed from the cluster automatically.

---

### Create a Pipeline job to lint Terraform files in Github repo

To ensure my Terraform files are linted correctly I wanted to create a Pipeline job to poll the repo every 10 minutes to make sure the Terraform files were valid:

`Create job > Pipline`

```
Name: Terraform Validate Check
Description: Pipeline job to validate Terraform files in my Github repo every 10 minutes
Triggers: Poll SCM
- Schedule: H/10 * * * *
Pipeline
- Definition: Pipeline script
Script:
pipeline {
  agent {
    kubernetes {
      label 'terraform-lint-agent'
      yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: terraform
    image: hashicorp/terraform:latest
    command:
    - cat
    tty: true
"""
    }
  }

  stages {

    stage('Checkout') {
      steps {
        git branch: 'main', url: 'https://github.com/<username>/Terraform.git' # Add Terrform repo here
      }
    }

    stage('Lint Terraform') {
      steps {
        container('terraform') {
          sh '''
echo 'Validating Terraform code recursively...'

# Loop over directories containing .tf files
find . -type f -name '*.tf' -exec dirname {} \\; | sort -u | while read dir; do
  echo "Linting Terraform in '$dir'..."
  cd "$dir"

  # Initialize Terraform
  terraform init -input=false -backend=false

  # Validate syntax only (variable references may be ignored)
  terraform validate || true

  cd -
done
'''
        }
      }
    }
  }

  post {
    success {
      echo "Terraform syntax check completed."
    }
    failure {
      echo "Terraform linting encountered errors. Check console output."
    }
  }
}
```

This will check the repo for all Terraform files, pull the repo, and using `terraform init -input=false -backend=false` means it will not initiate a backend but will download the necessary providers.
This will also use a different image for the container `hashicorp/terraform:latest` instead of Kaniko as was used previously.

---

## Summary

With this mini project complete my next task will be to set up a Certificate Manager for Kubernetes using ArgoCD.