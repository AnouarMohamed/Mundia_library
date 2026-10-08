SHELL := /bin/bash
.DEFAULT_GOAL := help
.NOTPARALLEL: ci ci-fast security-ci images-scan

DATABASE_URL ?= postgresql://postgres:rootpassword@127.0.0.1:5432/library_management
MIGRATION_DATABASE_NAME ?= circulation_migration_ci
MIGRATION_DATABASE_URL ?= postgresql://postgres:rootpassword@127.0.0.1:5432/$(MIGRATION_DATABASE_NAME)
MIGRATION_DATABASE_JDBC_URL ?= jdbc:postgresql://127.0.0.1:5432/$(MIGRATION_DATABASE_NAME)
MIGRATION_DATABASE_USERNAME ?= postgres
MIGRATION_DATABASE_PASSWORD ?= rootpassword
NODE := node
NPM := npm
GRADLE := ./gradlew
TRIVY_IMAGE := aquasec/trivy:0.72.0
TRIVY_CACHE_VOLUME := mundia-library-trivy-cache

.PHONY: help toolchain bootstrap contracts database-ci spa-ci spa-release web-ci-fast web-ci services-ci migration-tool-ci platform-ci images security-fs images-scan security-ci ci-fast ci

WEB_CI_ENV := \
	APP_ENV=development \
	DATABASE_URL=$(DATABASE_URL) \
	NEXTAUTH_SECRET=local-ci-secret-local-ci-secret \
	AUTH_SECRET=local-ci-secret-local-ci-secret \
	NEXTAUTH_URL=http://127.0.0.1:3100 \
	NEXT_PUBLIC_API_ENDPOINT=http://127.0.0.1:3100 \
	NEXT_PUBLIC_PROD_API_ENDPOINT=http://127.0.0.1:3100 \
	NEXT_PUBLIC_IMAGEKIT_URL_ENDPOINT=https://example.com \
	IMAGEKIT_PRIVATE_KEY=local-ci-private-key \
	UPSTASH_REDIS_URL=https://example.upstash.io \
	UPSTASH_REDIS_TOKEN=local-ci-token \
	QSTASH_URL=https://qstash.upstash.io \
	QSTASH_TOKEN=local-ci-token \
	BREVO_API_KEY=local-ci-token \
	BREVO_SENDER_EMAIL=noreply@example.com \
	RESEND_TOKEN=local-ci-token \
	ENABLE_WORKFLOWS=false

E2E_CI_ENV := \
	E2E_USER_EMAIL=test@user.com \
	E2E_USER_PASSWORD=12345678 \
	E2E_ADMIN_EMAIL=test@admin.com \
	E2E_ADMIN_PASSWORD=12345678 \
	E2E_PENDING_EMAIL=pending-e2e@example.test \
	E2E_PENDING_PASSWORD=12345678 \
	E2E_OTHER_USER_ID=00000000-0000-4000-8000-000000000002

help: ## Show supported local automation targets
	@awk 'BEGIN {FS = ":.*## "; printf "Mundia local automation\n\n"} /^[a-zA-Z0-9_-]+:.*## / {printf "  %-20s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

toolchain: ## Fail fast unless required CI tooling and Node 24.17+ are available
	@$(NODE) scripts/check-ci-toolchain.mjs

bootstrap: toolchain ## Install reproducible root and migration-tool dependencies
	@$(NPM) ci --no-audit --no-fund
	@$(NPM) run deps:build-native
	@$(MAKE) -C tools/circulation-migration install-ci

contracts: ## Validate committed OpenAPI JSON contracts and patch hygiene
	@jq empty services/catalog-service/src/main/resources/static/openapi/catalog-v1.json
	@jq empty services/circulation-service/src/main/resources/static/openapi/circulation-v1.json
	@jq empty services/digital-content-service/src/main/resources/static/openapi/digital-content-v1.json
	@jq empty services/membership-service/src/main/resources/static/openapi/membership-v1.json
	@jq empty services/notification-service/src/main/resources/static/openapi/notification-v1.json
	@jq empty services/web-bff/src/main/resources/static/openapi/web-bff-v1.json
	@git diff --check

database-ci: toolchain ## Rehearse legacy PostgreSQL migrations, seed, and concurrency invariants
	@docker compose up -d --wait db
	@DATABASE_URL=$(DATABASE_URL) $(NPM) run db:migrate
	@DATABASE_URL=$(DATABASE_URL) $(NPM) run db:verify-schema
	@DATABASE_URL=$(DATABASE_URL) ALLOW_TEST_FIXTURES=true $(NPM) run seed
	@DATABASE_URL=$(DATABASE_URL) $(NPM) run db:verify-concurrency
	@DATABASE_URL=$(DATABASE_URL) $(NPM) run db:verify-rate-limits

spa-ci: toolchain contracts ## Generate, typecheck, test, and build the static React migration shell
	@$(NPM) run spa:build
	@$(NPM) run spa:test

spa-release: spa-ci ## Produce the checksummed immutable SPA deployment artifact
	@$(NPM) run spa:package

web-ci-fast: toolchain ## Run web gates with database-dependent browser cases disabled
	@$(WEB_CI_ENV) $(NPM) run ci:quality

web-ci: toolchain ## Run every legacy web gate against the prepared CI database
	@$(WEB_CI_ENV) $(E2E_CI_ENV) $(NPM) run ci:quality

services-ci: toolchain contracts ## Compile, test, and package all Kotlin services
	@cd services && $(GRADLE) clean check bootJar --no-daemon --no-parallel

migration-tool-ci: toolchain ## Rehearse the circulation migration tool against PostgreSQL 18
	@docker compose up -d --wait db
	@case "$(MIGRATION_DATABASE_NAME)" in \
		circulation_migration_*) ;; \
		*) echo "refusing to recreate non-test migration database: $(MIGRATION_DATABASE_NAME)" >&2; exit 1 ;; \
	esac
	@docker compose exec -T db dropdb --if-exists --force \
		-U $(MIGRATION_DATABASE_USERNAME) $(MIGRATION_DATABASE_NAME)
	@docker compose exec -T db createdb \
		-U $(MIGRATION_DATABASE_USERNAME) $(MIGRATION_DATABASE_NAME)
	@$(MAKE) -C tools/circulation-migration check-ci
	@cd services && $(GRADLE) :circulation-service:bootJar --no-daemon
	@cd services && \
		APP_MIGRATION_ONLY=true \
		DATABASE_MIGRATION_URL=$(MIGRATION_DATABASE_JDBC_URL) \
		DATABASE_MIGRATION_USERNAME=$(MIGRATION_DATABASE_USERNAME) \
		DATABASE_MIGRATION_PASSWORD=$(MIGRATION_DATABASE_PASSWORD) \
		$(GRADLE) :circulation-service:bootRun --no-daemon
	@cd tools/circulation-migration && \
		CIRCULATION_MIGRATION_REQUIRE_INTEGRATION=true \
		CIRCULATION_MIGRATION_TEST_URL=$(MIGRATION_DATABASE_URL) \
		CIRCULATION_MIGRATION_TEST_DATABASE=$(MIGRATION_DATABASE_NAME) \
		$(NPM) run test:integration

platform-ci: toolchain ## Validate Helm, Kubernetes, policy, and GitOps contracts
	@platform/scripts/validate.sh

images: toolchain ## Build every image produced by push CI
	@docker build --pull --tag mundia-library:local \
		--build-arg NEXT_PUBLIC_API_ENDPOINT=http://localhost:3000 \
		--build-arg NEXT_PUBLIC_PROD_API_ENDPOINT=http://localhost:3000 .
	@docker build --pull --file services/circulation-service/Dockerfile --tag mundia-circulation-service:local services
	@docker build --pull --file services/membership-service/Dockerfile --tag mundia-membership-service:local services
	@docker build --pull --file services/catalog-service/Dockerfile --tag mundia-catalog-service:local services
	@docker build --pull --file services/digital-content-service/Dockerfile --tag mundia-digital-content-service:local services
	@docker build --pull --file services/notification-service/Dockerfile --tag mundia-notification-service:local services
	@docker build --pull --file services/web-bff/Dockerfile --tag mundia-web-bff:local services

security-fs: toolchain ## Match the blocking hosted dependency, IaC, and secret scan
	@docker run --rm \
		--volume "$(CURDIR):/workspace:ro" \
		--volume "$(TRIVY_CACHE_VOLUME):/root/.cache/trivy" \
		--workdir /workspace \
		$(TRIVY_IMAGE) fs \
		--scanners vuln,misconfig,secret \
		--skip-dirs node_modules \
		--skip-dirs .next \
		--skip-dirs services/.gradle \
		--skip-dirs services/catalog-service/build \
		--skip-dirs services/circulation-service/build \
		--skip-dirs services/digital-content-service/build \
		--skip-dirs services/membership-service/build \
		--skip-dirs services/notification-service/build \
		--skip-dirs services/web-bff/build \
		--skip-dirs tools/circulation-migration/node_modules \
		--severity CRITICAL,HIGH \
		--exit-code 1 \
		--format table \
		.

images-scan: images ## Match the blocking hosted Trivy scan for every deployable image
	@set -euo pipefail; \
	for image in \
		mundia-library:local \
		mundia-circulation-service:local \
		mundia-membership-service:local \
		mundia-catalog-service:local \
		mundia-digital-content-service:local \
		mundia-notification-service:local \
		mundia-web-bff:local; do \
		docker run --rm \
			--volume /var/run/docker.sock:/var/run/docker.sock \
			--volume "$(TRIVY_CACHE_VOLUME):/root/.cache/trivy" \
			$(TRIVY_IMAGE) image \
			--timeout 15m \
			--pkg-types os,library \
			--severity CRITICAL,HIGH \
			--exit-code 1 \
			--format table \
			"$$image"; \
	done

security-ci: bootstrap security-fs images-scan ## Run all blocking dependency and container security gates locally

ci-fast: contracts services-ci web-ci-fast ## Run the primary code-quality gates without provisioning or image builds

ci: bootstrap database-ci contracts services-ci migration-tool-ci platform-ci web-ci security-fs images-scan ## Mirror blocking push CI and security scans locally
