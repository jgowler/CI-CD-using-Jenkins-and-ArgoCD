# CI/CD using Jenkins and ArgoCD
A project to deploy the simple Flask application to a Kubernetes cluster using ArgoCD and Jenkins.

---

## Part 1: Deploy ArgoCD to cluster

ArgoCd will be deployed using the Get Started guide provided here: https://argo-cd.readthedocs.io/en/stable/getting_started/

To run ArgoCD commands I installed the latest stable release running hte following commands:

```
VERSION=$(curl -L -s https://raw.githubusercontent.com/argoproj/argo-cd/stable/VERSION)
curl -sSL -o argocd-linux-amd64 https://github.com/argoproj/argo-cd/releases/download/v$VERSION/argocd-linux-amd64
sudo install -m 555 argocd-linux-amd64 /usr/local/bin/argocd
rm argocd-linux-amd64
```

As this is my own project I will be accessing the ArgoCD server from my local machine by changing the Service type from ClusterIP to NodePort:

```
kubectl -n argocd patch svc argocd-server -p '{"spec": {"type": "NodePort"}}'
```

As I did not specify the Port I needed to then use the following to get the correct port to access the service:

```
kubectl describe svc -n argocd argocd-server
```

Once I had the port I was able to access the server from my local machine using `<node-ip-address>:<Port>`

To log on to the ArgoCD server the password for the admin account is auto-generated and stored as clear text. This can be retrieved using:

```
argocd admin initial-password -n argocd
```

As suggested, the admin password was changed once logged in. As ArgoCD is installed on the same cluster it will be managing there was no need to register another, at least not right now.

Now ArgoCD is up and running, on to deploying a test application.

## Part 2: Deploy a test application using ArgoCD

To continue on from my previous project [deploying my own Flask application in Python using Kubernetes and Terraform](https://github.com/jgowler/Python-Terraform-Kubernetes-Project) I will be using the deployment YAML created here.

From the UI I applied the following settings:

```
Application name: "test"
Project: "default"
Sync Policy: "Manual"
Source: "https://github.com/jgowler/Python-Terraform-Kubernetes-Project.git"
Revision: "HEAD"
Path: "Kubernetes-Files"
Destination: "https://kubernetes.default.svc"
Namespace: "test-namespace"
```

With this set I created the deployment and it was all set up and ready to go. As the sync policy was set to Manual I needed to sync the deployment with the GitHub repo (the source of truth) and the application was up and running and available on port 30080 of the node it is deployed on.

Before continuing to the next step I wanted to see how ArgoCD handled changes in realtime, so I edited the deployment YAML in GitHub to show 3 replicas instead of 2. This was as simple as editing the application in ArgoCD and setting sync to auto.

## With ArgoCD up and running and synchronising with the Repo the next step is to deploy Jenkins.