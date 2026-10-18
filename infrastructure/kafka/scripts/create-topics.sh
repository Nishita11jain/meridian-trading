set -euo pipefail

BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
# Address the broker binds to and advertises. Defaults to the loopback address, so the script
# works on any machine; set PRIVATE_IP to your LAN address for clients on other hosts.
# (127.0.0.1 rather than "localhost": docker's -p flag only accepts an IP address.)
PRIVATE_IP="${PRIVATE_IP:-127.0.0.1}"

docker rm -f kafka >/dev/null 2>&1 || true

docker run -d --name kafka -p "${PRIVATE_IP}:9092:9092" \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://${PRIVATE_IP}:9092" \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@127.0.0.1:9093 \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false \
  -e CLUSTER_ID=4L6g3nShT-eMCtK--X86sw \
  apache/kafka:latest

echo "Waiting for Kafka broker to become ready..."
for i in $(seq 1 30); do
  if docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVER}" --list >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVER}" --create --if-not-exists \
                                                   --topic orders \
                                                   --partitions 3 \
                                                   --replication-factor 1 \
                                                   --config retention.ms=604800000

docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVER}" --create --if-not-exists \
  --topic trade-events \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=2592000000

docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVER}" --create --if-not-exists \
  --topic market-data \
  --partitions 6 \
  --replication-factor 1 \
  --config retention.ms=86400000

docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVER}" --create --if-not-exists \
  --topic orders.DLT \
  --partitions 1 \
  --replication-factor 1 \
  --config retention.ms=604800000

docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVER}" --create --if-not-exists \
  --topic trade-events.DLT \
  --partitions 1 \
  --replication-factor 1 \
  --config retention.ms=2592000000

docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "${BOOTSTRAP_SERVER}" --create --if-not-exists \
  --topic market-data.DLT \
  --partitions 1 \
  --replication-factor 1 \
  --config retention.ms=86400000
