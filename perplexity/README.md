# Event-Driven Notification System

This repository contains a scalable, event-driven notification system built with Spring Boot 3.x, Apache Kafka, Oracle DB, Redis, and Resilience4J. It processes alerts/messages from multiple upstream systems (via Kafka topics), applies system-specific business rules, and dispatches notifications through SMS, Email, OTP, Voice, and Push channels.

## High-level Overview

- Upstream systems publish alerts to dedicated Kafka topics (one or more topics per system).
- A stateless **Alert Orchestrator** service consumes these topics, normalizes and enriches events, applies per-system business logic, and routes them to per-channel Kafka topics.
- **Channel Dispatcher** services (SMS, Email, OTP, Voice, Push) consume channel-specific topics and integrate with external providers using resilient patterns (circuit breakers, retries).
- An **Audit** component continuously records message lifecycle, delivery status, and failure details into Oracle, with Redis used for caching hot data such as templates and user preferences.

The design targets 10k messages per second with horizontal scalability and strong observability.

## Services (initial cut)

- `alert-orchestrator-service`  
  - Consumes upstream Kafka topics (one per upstream system).
  - Normalizes alerts, enriches from Oracle/Redis, determines channels.
  - Publishes to per-channel topics and audit topics.

- `sms-dispatcher-service`  
- `email-dispatcher-service`  
- `otp-dispatcher-service`  
- `voice-dispatcher-service`  
- `push-dispatcher-service`  
  - Each consumes its own channel topic.
  - Dispatches to external providers with Resilience4J (retry, circuit-breaker, bulkhead).
  - Publishes status and errors to an audit topic.

- `audit-service` (optional initial phase; may be folded into orchestrator first)  
  - Consumes audit and DLQ topics.
  - Persists audit records to Oracle.
  - Exposes APIs for reporting and internal analysis.

## Core Technologies

- Java 17+ / Spring Boot 3.x
- Spring Kafka
- Spring Data JPA (Oracle)
- Redis (Spring Data Redis)
- Resilience4J
- OpenTelemetry / Micrometer for metrics and tracing
- Deployed on OpenShift (OCP) using ConfigMaps/Secrets for configuration

## Kafka Topics (initial)

- Upstream topics (examples):  
  - `alerts.systemA.input`  
  - `alerts.systemB.input`  
  - `alerts.systemC.input`  
- Normalized / internal topics (if used):  
  - `alerts.normalized`  
- Per-channel topics:  
  - `alerts.sms`  
  - `alerts.email`  
  - `alerts.otp`  
  - `alerts.voice`  
  - `alerts.push`  
- Retry & DLQ:  
  - `alerts.<channel>.retry.<n>`  
  - `alerts.dlq`  
- Audit:  
  - `alerts.audit`

## Running Locally

> NOTE: This is a starting point. Replace with actual commands once Docker/Compose or `skaffold` manifests are ready.

- Start infra (Kafka, Redis, Oracle test instance) using Docker Compose:
  ```bash
  docker compose up -d
