SHELL := /bin/bash
.DEFAULT_GOAL := help

.PHONY: help install-ci check-ci

help:
	@printf '%s\n' 'Targets: install-ci, check-ci'

install-ci:
	@npm ci --no-audit --no-fund
	@npm run deps:build-native

check-ci:
	@npm audit --audit-level=low
	@npm run check

