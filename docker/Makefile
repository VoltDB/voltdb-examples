# ============================================================================
# Makefile for Building VoltDB Example Docker Images
# ============================================================================
#
# Usage:
#   make build-all              # Build all example images
#   make build-voter            # Build single image
#   make push-all               # Push all images to registry
#   make test-voter             # Run voter with local VoltDB
#   make clean                  # Remove all built images
#
# Configuration:
#   DOCKERHUB_REPO=voltdb/...   # Docker Hub repo (default: voltdb/voltdb-enterprise-dev)
#   VERSION=1.0.0               # Image tag
#   VOLTDB_VERSION=15.0         # VoltDB image version
# ============================================================================

# Configuration
# Local builds produce images named: voltdb-example-{app}:{VERSION}
# Pushes go to a single Docker Hub repo, tagged as: {DOCKERHUB_REPO}:{app}-{VERSION}
#   e.g. voltdb/voltdb-enterprise-dev:voter-1.0.0
DOCKERHUB_REPO ?= voltdb/voltdb-enterprise-dev
VERSION ?= latest
VOLTDB_VERSION ?= 15.1.0
DOCKER_BUILD_ARGS ?= --progress=plain

# All apps that have Dockerfiles
APPS_WITH_DOCKERFILE = voter voltkv bank-offers client2 ddos-detection fraud-tx-detection \
                       adperformance callcenter fraud-detection geospatial \
                       json-sessions nbbo positionkeeper simple uniquedevices windowing

# Alias for all apps
ALL_APPS = $(APPS_WITH_DOCKERFILE)

# Build context is parent directory
BUILD_CONTEXT = ..

# ============================================================================
# Build Targets
# ============================================================================

.PHONY: all build-all push-all clean help

all: build-all

# Build all apps with existing Dockerfiles
build-all: $(addprefix build-,$(APPS_WITH_DOCKERFILE))
	@echo "All images built successfully"

# Generic build target
build-%:
	@echo "Building voltdb-example-$*:$(VERSION)..."
	docker build $(DOCKER_BUILD_ARGS) \
		-t voltdb-example-$*:$(VERSION) \
		-t voltdb-example-$*:latest \
		-f $(BUILD_CONTEXT)/$*/Dockerfile \
		$(BUILD_CONTEXT)

# ============================================================================
# Push Targets
# ============================================================================

push-all: $(addprefix push-,$(APPS_WITH_DOCKERFILE))
	@echo "All images pushed successfully to $(DOCKERHUB_REPO)"

push-%:
	@echo "Pushing voltdb-example-$* to $(DOCKERHUB_REPO):$*-$(VERSION)..."
	docker tag voltdb-example-$*:$(VERSION) $(DOCKERHUB_REPO):$*-$(VERSION)
	docker push $(DOCKERHUB_REPO):$*-$(VERSION)

# ============================================================================
# Test Targets (using docker-compose)
# ============================================================================

.PHONY: test-voter test-voltkv up-voltdb down

# Start VoltDB container
up-voltdb:
	docker-compose -f docker-compose.yml up -d voltdb
	@echo "Waiting for VoltDB to be ready..."
	@sleep 30

down:
	docker-compose -f docker-compose.yml down -v

# Test individual apps
test-voter: build-voter up-voltdb
	docker-compose -f docker-compose.yml run --rm voter
	@make down

test-voltkv: build-voltkv up-voltdb
	docker-compose -f docker-compose.yml run --rm voltkv
	@make down

test-%: build-% up-voltdb
	docker-compose -f docker-compose.yml run --rm $*
	@make down

# ============================================================================
# Kubernetes Targets
# ============================================================================

.PHONY: k8s-apply k8s-delete k8s-run-voter

k8s-apply:
	kubectl apply -k k8s/

k8s-delete:
	kubectl delete -k k8s/ --ignore-not-found

k8s-run-voter:
	kubectl apply -f k8s/job-voter.yaml
	kubectl wait --for=condition=complete job/voter-benchmark -n voltdb-examples --timeout=600s
	kubectl logs -f job/voter-benchmark -n voltdb-examples

# ============================================================================
# Clean Targets
# ============================================================================

clean:
	@for app in $(APPS_WITH_DOCKERFILE); do \
		echo "Removing voltdb-example-$$app..."; \
		docker rmi voltdb-example-$$app:$(VERSION) 2>/dev/null || true; \
		docker rmi voltdb-example-$$app:latest 2>/dev/null || true; \
	done

# ============================================================================
# Generate Dockerfiles for remaining apps
# ============================================================================

.PHONY: generate-dockerfiles

generate-dockerfiles:
	./scripts/generate-dockerfiles.sh

# ============================================================================
# Help
# ============================================================================

help:
	@echo "VoltDB Examples Docker Build System"
	@echo ""
	@echo "Build targets:"
	@echo "  build-all          Build all Docker images locally"
	@echo "  build-<app>        Build specific app (e.g., build-voter)"
	@echo ""
	@echo "Push targets (to Docker Hub):"
	@echo "  push-all           Tag and push all images to $(DOCKERHUB_REPO)"
	@echo "  push-<app>         Tag and push specific app as $(DOCKERHUB_REPO):<app>-$(VERSION)"
	@echo "  Example: VERSION=1.0.0 make push-voter"
	@echo ""
	@echo "Test targets:"
	@echo "  test-<app>         Build and test app with local VoltDB"
	@echo "  up-voltdb          Start VoltDB container"
	@echo "  down               Stop and remove containers"
	@echo ""
	@echo "Kubernetes targets:"
	@echo "  k8s-apply          Apply Kubernetes manifests"
	@echo "  k8s-delete         Delete Kubernetes resources"
	@echo "  k8s-run-voter      Run voter benchmark as K8s job"
	@echo ""
	@echo "Other targets:"
	@echo "  clean              Remove locally built images"
	@echo "  generate-dockerfiles  Generate Dockerfiles for remaining apps"
	@echo ""
	@echo "Local images are named: voltdb-example-{app}:latest"
	@echo ""
	@echo "Apps with Dockerfiles: $(APPS_WITH_DOCKERFILE)"
