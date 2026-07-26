{{- define "mundia-service.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "mundia-service.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name (include "mundia-service.name" .) | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "mundia-service.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "mundia-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: mundiapolis-library
app.kubernetes.io/version: {{ .Values.image.digest | trunc 15 | quote }}
mundiapolis.io/environment: {{ .Values.environment }}
{{- end }}

{{- define "mundia-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "mundia-service.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "mundia-service.runtimeSelectorLabels" -}}
{{ include "mundia-service.selectorLabels" . }}
app.kubernetes.io/component: runtime
{{- end }}

{{- define "mundia-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "mundia-service.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- required "serviceAccount.name is required when creation is disabled" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "mundia-service.migrationServiceAccountName" -}}
{{- default (printf "%s-migration" (include "mundia-service.fullname" .)) .Values.migration.serviceAccount.name }}
{{- end }}

{{- define "mundia-service.validate" -}}
{{- if not (regexMatch "^sha256:[a-f0-9]{64}$" .Values.image.digest) }}
{{- fail "image.digest must be an immutable sha256 digest" }}
{{- end }}
{{- if and (eq .Values.environment "prod") (lt (int .Values.replicaCount) 3) }}
{{- fail "production requires at least three replicas" }}
{{- end }}
{{- if and (eq .Values.environment "prod") (not .Values.autoscaling.enabled) }}
{{- fail "production requires autoscaling" }}
{{- end }}
{{- if and (eq .Values.environment "prod") (not .Values.podDisruptionBudget.enabled) }}
{{- fail "production requires a PodDisruptionBudget" }}
{{- end }}
{{- if and (eq .Values.environment "prod") (not .Values.networkPolicy.enabled) }}
{{- fail "production requires a NetworkPolicy" }}
{{- end }}
{{- if and (eq .Values.environment "prod") (not .Values.externalSecret.enabled) }}
{{- fail "production requires External Secrets" }}
{{- end }}
{{- if and (eq .Values.environment "prod") (not .Values.migration.enabled) }}
{{- fail "production requires the pre-sync migration Job" }}
{{- end }}
{{- if .Values.migration.enabled }}
{{- if not .Values.migration.externalSecret.enabled }}
{{- fail "migration mode requires its dedicated ExternalSecret" }}
{{- end }}
{{- if not .Values.migration.serviceAccount.create }}
{{- fail "migration mode requires a chart-managed no-token ServiceAccount" }}
{{- end }}
{{- if .Values.migration.serviceAccount.automountToken }}
{{- fail "migration ServiceAccount token automount must remain disabled" }}
{{- end }}
{{- if not .Values.migration.networkPolicy.enabled }}
{{- fail "migration mode requires a dedicated NetworkPolicy" }}
{{- end }}
{{- if not .Values.migration.cleanup.enabled }}
{{- fail "migration mode requires post-migration credential cleanup" }}
{{- end }}
{{- range .Values.migration.networkPolicy.egressCidrs }}
{{- if eq .cidr "0.0.0.0/0" }}
{{- fail "migration NetworkPolicy may not allow 0.0.0.0/0" }}
{{- end }}
{{- range .ports }}
{{- if or (ne (.port | int) 5432) (ne .protocol "TCP") }}
{{- fail "migration NetworkPolicy may only allow TCP/5432 plus chart-managed DNS" }}
{{- end }}
{{- end }}
{{- end }}
{{- range .Values.migration.cleanup.apiServerCidrs }}
{{- if eq . "0.0.0.0/0" }}
{{- fail "migration cleanup may not use unrestricted API-server egress" }}
{{- end }}
{{- end }}
{{- if eq .Values.externalSecret.targetName .Values.migration.externalSecret.targetName }}
{{- fail "runtime and migration ExternalSecrets must have different target names" }}
{{- end }}
{{- if ne (index .Values.env "SPRING_FLYWAY_ENABLED" | toString) "false" }}
{{- fail "runtime pods must set SPRING_FLYWAY_ENABLED=false when migration mode is enabled" }}
{{- end }}
{{- if hasKey .Values.env "APP_MIGRATION_ONLY" }}
{{- fail "APP_MIGRATION_ONLY belongs only on the migration Job" }}
{{- end }}
{{- end }}
{{- if and .Values.ingress.enabled (empty .Values.ingress.tls) }}
{{- fail "an enabled ingress requires TLS configuration" }}
{{- end }}
{{- range .Values.networkPolicy.egressCidrs }}
{{- if and (eq $.Values.environment "prod") (eq .cidr "0.0.0.0/0") }}
{{- fail "production NetworkPolicy may not allow 0.0.0.0/0" }}
{{- end }}
{{- end }}
{{- end }}
