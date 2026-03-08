#!/bin/bash
# ═══════════════════════════════════════════════════════════
# Kafka Topic Creation Script (Enterprise — Max 10 Partitions)
# ═══════════════════════════════════════════════════════════

KAFKA_BOOTSTRAP=${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}
REPLICATION_FACTOR=${KAFKA_REPLICATION_FACTOR:-3}
MAX_PARTITIONS=10

echo "Creating Kafka topics on $KAFKA_BOOTSTRAP (RF=$REPLICATION_FACTOR, max partitions=$MAX_PARTITIONS)..."
echo ""

# ── Inbound topics (one per upstream) ──
for UPSTREAM in banking insurance otp-auth marketing logistics payments; do
  kafka-topics.sh --create --if-not-exists \
    --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --topic "inbound.$UPSTREAM" \
    --partitions 10 \
    --replication-factor "$REPLICATION_FACTOR" \
    --config retention.ms=604800000 \
    --config cleanup.policy=delete \
    --config min.insync.replicas=2 \
    --config message.timestamp.type=CreateTime
  echo "  ✓ inbound.$UPSTREAM       (10 partitions)"
done

# ── Processing topic ──
kafka-topics.sh --create --if-not-exists \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --topic processing.events \
  --partitions 10 \
  --replication-factor "$REPLICATION_FACTOR" \
  --config retention.ms=259200000 \
  --config min.insync.replicas=2
echo "  ✓ processing.events        (10 partitions)"

# ── Dispatch topics ──
declare -A CHANNEL_PARTITIONS=(
  ["sms"]=10
  ["email"]=10
  ["otp"]=6          # lower volume, time-critical
  ["voice"]=4         # lowest volume
  ["webhook"]=10
  ["push"]=10
)

declare -A CHANNEL_RETENTION=(
  ["sms"]=259200000    # 3 days
  ["email"]=259200000
  ["otp"]=86400000     # 1 day (time-sensitive)
  ["voice"]=259200000
  ["webhook"]=259200000
  ["push"]=259200000
)

for CHANNEL in "${!CHANNEL_PARTITIONS[@]}"; do
  kafka-topics.sh --create --if-not-exists \
    --bootstrap-server "$KAFKA_BOOTSTRAP" \
    --topic "dispatch.$CHANNEL" \
    --partitions "${CHANNEL_PARTITIONS[$CHANNEL]}" \
    --replication-factor "$REPLICATION_FACTOR" \
    --config retention.ms="${CHANNEL_RETENTION[$CHANNEL]}" \
    --config min.insync.replicas=2
  echo "  ✓ dispatch.$CHANNEL          (${CHANNEL_PARTITIONS[$CHANNEL]} partitions)"
done

# ── Retry topic ──
kafka-topics.sh --create --if-not-exists \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --topic retry.events \
  --partitions 6 \
  --replication-factor "$REPLICATION_FACTOR" \
  --config retention.ms=604800000 \
  --config min.insync.replicas=2
echo "  ✓ retry.events              (6 partitions)"

# ── Dead Letter Topic ──
kafka-topics.sh --create --if-not-exists \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --topic dlt.events \
  --partitions 4 \
  --replication-factor "$REPLICATION_FACTOR" \
  --config retention.ms=2592000000 \
  --config min.insync.replicas=2
echo "  ✓ dlt.events                (4 partitions)"

# ── Audit topic ──
kafka-topics.sh --create --if-not-exists \
  --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --topic audit.events \
  --partitions 10 \
  --replication-factor "$REPLICATION_FACTOR" \
  --config retention.ms=1209600000 \
  --config min.insync.replicas=2
echo "  ✓ audit.events              (10 partitions)"

echo ""
echo "═══════════════════════════════════════════════════════"
echo "All topics created. Summary:"
echo "  Inbound:    6 topics × 10 partitions"
echo "  Processing: 1 topic  × 10 partitions"
echo "  Dispatch:   6 topics × 4-10 partitions"
echo "  Retry:      1 topic  × 6 partitions"
echo "  DLT:        1 topic  × 4 partitions"
echo "  Audit:      1 topic  × 10 partitions"
echo ""
echo "Recommended pod config:"
echo "  concurrency=5, pods=2 (for 10-partition topics)"
echo "  concurrency=3, pods=2 (for 6-partition topics)"
echo "  concurrency=2, pods=2 (for 4-partition topics)"
echo "═══════════════════════════════════════════════════════"
echo ""
echo "Verify: kafka-topics.sh --list --bootstrap-server $KAFKA_BOOTSTRAP"
