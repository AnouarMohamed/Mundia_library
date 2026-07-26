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

{{- define "mundia-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "mundia-service.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- required "serviceAccount.name is required when creation is disabled" .Values.serviceAccount.name }}
{{- end }}
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
{{- if and .Values.ingress.enabled (empty .Values.ingress.tls) }}
{{- fail "an enabled ingress requires TLS configuration" }}
{{- end }}
{{- range .Values.networkPolicy.egressCidrs }}
{{- if and (eq $.Values.environment "prod") (eq .cidr "0.0.0.0/0") }}
{{- fail "production NetworkPolicy may not allow 0.0.0.0/0" }}
{{- end }}
{{- end }}
{{- end }}

