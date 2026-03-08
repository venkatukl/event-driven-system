#!/bin/bash
# ═══════════════════════════════════════════════════════════
# Kafka Topic Creation Script
# Run once per environment (dev/staging/prod)
# Adjust partitions and replication factor per environment
# ═══════════════════════════════════════════════════════════

KAFKA_BOOTSTRAP=${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}
REPLICATION_FACTOR=${KAFKA_REPLICATION_FACTOR:-3}

echo "Creating Kafka topics on $KAFKA_BOOTSTRAP (RF=$REPLICATION_FACTOR)..."

# ── Inbound topics (one per upstream, 12 partitions each) ──
for UPSTREAM in banking insurance otp-auth marketing logistics payments; do
  kafka-topics.sh --create --if-not-exists \
    --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --topic "inbound.$UPSTREAM" \
    --partitions 12 \
    --replication-factor "$REPLICATION_FACTOR" \
    --config retention.ms=604800000 \
    --config cleanup.policy=delete \
    --config min.insync.replicas=2 \
    --config message.timestamp.type=CreateTime
  echo "  ✓ inbound.$UPSTREAM"
done

# ── Processing topic (24 partitions for higher throughput) ──
kafka-topics.sh --create --if-not-exists \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --topic processing.events \
  --partitions 24 \
  --replication-factor "$REPLICATION_FACTOR" \
  --config retention.ms=259200000 \
  --config min.insync.replicas=2
echo "  ✓ processing.events"

# ── Dispatch topics (per channel) ──
for CHANNEL in sms email otp voice webhook push; do
  PARTITIONS=12
  RETENTION_MS=259200000  # 3 days

  # OTP and voice have fewer partitions
  if [ "$CHANNEL" = "otp" ] || [ "$CHANNEL" = "voice" ]; then
    PARTITIONS=6
  fi
  # OTP has shorter retention (time-sensitive)
  if [ "$CHANNEL" = "otp" ]; then
    RETENTION_MS=86400000  # 1 day
  fi

  kafka-topics.sh --create --if-not-exists \
    --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --topic "dispatch.$CHANNEL" \
    --partitions "$PARTITIONS" \
    --replication-factor "$REPLICATION_FACTOR" \
    --config retention.ms="$RETENTION_MS" \
    --config min.insync.replicas=2
  echo "  ✓ dispatch.$CHANNEL (partitions=$PARTITIONS)"
done

# ── Retry topic ──
kafka-topics.sh --create --if-not-exists \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --topic retry.events \
  --partitions 12 \
  --replication-factor "$REPLICATION_FACTOR" \
  --config retention.ms=604800000 \
  --config min.insync.replicas=2
echo "  ✓ retry.events"

# ── Dead Letter Topic ──
kafka-topics.sh --create --if-not-exists \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --topic dlt.events \
  --partitions 6 \
  --replication-factor "$REPLICATION_FACTOR" \
  --config retention.ms=2592000000 \
  --config min.insync.replicas=2
echo "  ✓ dlt.events"

# ── Audit topic ──
kafka-topics.sh --create --if-not-exists \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --topic audit.events \
  --partitions 12 \
  --replication-factor "$REPLICATION_FACTOR" \
  --config retention.ms=1209600000 \
  --config min.insync.replicas=2
echo "  ✓ audit.events"

echo ""
echo "All topics created successfully."
echo ""
echo "Verify with: kafka-topics.sh --list --bootstrap-server $KAFKA_BOOTSTRAP"
