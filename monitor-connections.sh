#!/bin/bash

# Connection Monitor Script
# Run this in a separate terminal to watch connections in real-time

OPENSEARCH_URL="http://localhost:9210"
MODEL_ID="kOSFJpsBp_IfIhYUtW6s"
ADMIN_USER="admin"
ADMIN_PASS="admin"

echo "Monitoring connections and model state..."
echo "Press Ctrl+C to stop"
echo ""

while true; do
    # Get model state
    model_state=$(curl -s -u "${ADMIN_USER}:${ADMIN_PASS}" \
        "${OPENSEARCH_URL}/_plugins/_ml/models/${MODEL_ID}" 2>/dev/null \
        | jq -r '.model_state // "UNKNOWN"')

    # Count connections
    tcp_connections=$(netstat -an 2>/dev/null | grep ESTABLISHED | grep -c ":9210\|:443\|:80" || echo "0")

    # Count file descriptors (if lsof available)
    opensearch_pid=$(pgrep -f opensearch | head -1)
    if [ -n "$opensearch_pid" ]; then
        fd_count=$(lsof -p $opensearch_pid 2>/dev/null | wc -l | tr -d ' ')
    else
        fd_count="N/A"
    fi

    # Display
    timestamp=$(date '+%H:%M:%S')
    printf "[%s] Model: %-12s | TCP Conns: %-4s | FDs: %-6s\n" \
        "$timestamp" "$model_state" "$tcp_connections" "$fd_count"

    sleep 2
done
