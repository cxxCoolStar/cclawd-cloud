# Distributed Channel Runtime

Build and publish the image, then create the required secret before applying the base manifests.

```bash
docker build -t registry.example.com/openagent:0.1.0 .
docker push registry.example.com/openagent:0.1.0
kubectl create secret generic openagent-channel-secrets \
  --from-literal=OPENAGENT_DATABASE_URL='jdbc:postgresql://postgres:5432/openagent' \
  --from-literal=OPENAGENT_DATABASE_USERNAME='openagent' \
  --from-literal=OPENAGENT_DATABASE_PASSWORD='replace-me' \
  --from-literal=OPENAGENT_REDIS_HOST='redis' \
  --from-literal=OPENAGENT_REDIS_PASSWORD='replace-me' \
  --from-literal=OPENAGENT_MODEL_API_KEY='replace-me'
kubectl apply -k deploy/kubernetes
```

Set the production image with a Kustomize overlay instead of editing the base Deployment files. PostgreSQL and Redis must be reachable before the readiness probes become healthy. The Gateway and Worker deployments use the same image; `OPENAGENT_CHANNEL_ROLES` selects the runtime responsibilities.

The containers run with a read-only root filesystem. Ephemeral runtime files use the mounted `/tmp` and `/app/workspace` directories; the eval workspace is explicitly redirected to `/tmp/openagent-eval-workspaces`.

The important metrics are available from the Actuator metrics endpoint:

- `openagent.channel.inbox.backlog`
- `openagent.channel.outbox.backlog`
- `openagent.channel.inbox.interrupted`
- `openagent.channel.inbox.dead`
- `openagent.channel.outbox.dead`
- `openagent.channel.leases.held`
- `openagent.channel.leases.acquired`
- `openagent.channel.leases.lost`
