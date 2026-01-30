#!/bin/bash

# Connection Leak Reproduction Script
# Tests model undeploy/redeploy cycles to trigger httpClient leak

set -e

# Configuration
OPENSEARCH_URL="http://k8s-olly3dat-opensear-05fb0c6919-309672183.us-east-1.elb.amazonaws.com:80"
MODEL_ID="XTW5kpsB-wK_Dq7qE4au"
CYCLES=15
ADMIN_USER="admin"
ADMIN_PASS="myStrongPassword123!"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Connection Leak Reproduction Test${NC}"
echo -e "${GREEN}========================================${NC}"
echo "OpenSearch: $OPENSEARCH_URL"
echo "Model ID: $MODEL_ID"
echo "Test Cycles: $CYCLES"
echo ""

# Function to make authenticated requests
make_request() {
    local method=$1
    local endpoint=$2
    local data=$3

    if [ -z "$data" ]; then
        curl -s -X "$method" \
            -u "${ADMIN_USER}:${ADMIN_PASS}" \
            "${OPENSEARCH_URL}${endpoint}"
    else
        curl -s -X "$method" \
            -u "${ADMIN_USER}:${ADMIN_PASS}" \
            -H "Content-Type: application/json" \
            -d "$data" \
            "${OPENSEARCH_URL}${endpoint}"
    fi
}

# Function to check model state
check_model_state() {
    local state=$(make_request GET "/_plugins/_ml/models/${MODEL_ID}" | jq -r '.model_state // "UNKNOWN"')
    echo "$state"
}

# Function to wait for model state
wait_for_state() {
    local target_state=$1
    local max_wait=$2
    local waited=0

    while [ $waited -lt $max_wait ]; do
        local current_state=$(check_model_state)
        if [ "$current_state" == "$target_state" ]; then
            return 0
        fi
        echo -n "."
        sleep 2
        waited=$((waited + 2))
    done

    echo ""
    echo -e "${RED}Timeout waiting for state: $target_state${NC}"
    return 1
}

# Function to test prediction
test_prediction() {
    local response=$(make_request POST "/_plugins/_ml/models/${MODEL_ID}/_predict" \
        '{"parameters": {"system_prompt": "You are a helpful assistant.", "prompt": "Hello, how are you?"}}' 2>&1)

    if echo "$response" | grep -q "Acquire operation took longer"; then
        echo -e "${RED}✗ CONNECTION LEAK DETECTED: Acquire timeout error!${NC}"
        echo "$response"
        return 1
    elif echo "$response" | grep -q "security token.*expired"; then
        echo -e "${YELLOW}⚠ Auth token expired (not a leak issue)${NC}"
        return 2
    elif echo "$response" | grep -q "error"; then
        echo -e "${YELLOW}! Prediction error: $(echo $response | jq -r '.error.reason // .error' 2>/dev/null || echo $response)${NC}"
        return 1
    else
        echo -e "${GREEN}✓ Prediction successful${NC}"
        return 0
    fi
}

# Function to count network connections
count_connections() {
    local count=$(netstat -an 2>/dev/null | grep ESTABLISHED | grep -E ":(9210|443|80)" | wc -l | tr -d ' ')
    if [ -z "$count" ] || [ "$count" = "" ]; then
        count=0
    fi
    echo "$count"
}

# Initial check
echo -e "${YELLOW}Initial State Check:${NC}"
initial_state=$(check_model_state)
echo "Model state: $initial_state"
initial_connections=$(count_connections)
echo "Active connections: $initial_connections"
echo ""

# If model is not deployed, deploy it first
if [ "$initial_state" != "DEPLOYED" ]; then
    echo -e "${YELLOW}Model not deployed. Deploying...${NC}"
    make_request POST "/_plugins/_ml/models/${MODEL_ID}/_deploy" > /dev/null
    wait_for_state "DEPLOYED" 60
    echo -e "${GREEN}Model deployed${NC}"
    sleep 5
fi

# Test initial prediction
echo -e "${YELLOW}Testing initial prediction:${NC}"
test_prediction
echo ""

# Main leak reproduction loop
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Starting Undeploy/Redeploy Cycles${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

leak_detected=false

for i in $(seq 1 $CYCLES); do
    echo -e "${YELLOW}--- Cycle $i/$CYCLES ---${NC}"

    # Undeploy (this leaks the httpClient)
    echo -n "Undeploying model..."
    undeploy_response=$(make_request POST "/_plugins/_ml/models/${MODEL_ID}/_undeploy")
    echo " done"

    # Wait for undeploy
    if ! wait_for_state "UNDEPLOYED" 30; then
        echo -e "${RED}Failed to undeploy in cycle $i${NC}"
        continue
    fi
    echo -e "${GREEN}Model undeployed${NC}"
    sleep 2

    # Redeploy (this creates a new httpClient, old one leaked)
    echo -n "Redeploying model..."
    deploy_response=$(make_request POST "/_plugins/_ml/models/${MODEL_ID}/_deploy")
    echo " done"

    # Wait for deploy
    if ! wait_for_state "DEPLOYED" 60; then
        echo -e "${RED}Failed to deploy in cycle $i${NC}"
        continue
    fi
    echo -e "${GREEN}Model deployed${NC}"
    sleep 3

    # Check connections
    current_connections=$(count_connections)
    echo "Active connections: $current_connections (initial: $initial_connections)"

    # Test prediction after every 3 cycles
    if [ $((i % 3)) -eq 0 ]; then
        echo "Testing prediction after cycle $i..."
        test_prediction
        pred_result=$?
        if [ $pred_result -eq 1 ]; then
            # Actual error (not auth), might be leak
            leak_detected=true
            echo -e "${RED}Leak may have been detected at cycle $i!${NC}"
            break
        fi
        # If result is 2 (auth error), continue testing
    fi

    # Check if connections are accumulating (sign of leak)
    current_connections=${current_connections:-0}
    initial_connections=${initial_connections:-0}
    connection_increase=$((current_connections - initial_connections))
    expected_for_cycle=$((i * 20))
    if [ $connection_increase -gt $expected_for_cycle ]; then
        echo -e "${YELLOW}⚠ Suspicious connection growth: +${connection_increase} connections${NC}"
    fi

    echo ""
done

# Final checks
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Final Verification${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

final_connections=$(count_connections)
echo "Initial connections: $initial_connections"
echo "Final connections: $final_connections"
echo "Connection increase: $((final_connections - initial_connections))"
echo ""

# Test prediction multiple times
echo -e "${YELLOW}Testing prediction 5 times to detect timeout errors:${NC}"
success_count=0
failure_count=0
auth_error_count=0

for i in $(seq 1 5); do
    echo -n "Test $i: "
    test_prediction
    result=$?
    if [ $result -eq 0 ]; then
        success_count=$((success_count + 1))
    elif [ $result -eq 2 ]; then
        auth_error_count=$((auth_error_count + 1))
    else
        failure_count=$((failure_count + 1))
        leak_detected=true
    fi
    sleep 1
done

echo ""
echo "Successful predictions: $success_count/5"
echo "Auth errors: $auth_error_count/5"
echo "Timeout/leak errors: $failure_count/5"
echo ""

# Summary
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Test Summary${NC}"
echo -e "${GREEN}========================================${NC}"

# Ensure numeric values
initial_connections=${initial_connections:-0}
final_connections=${final_connections:-0}

connection_increase=$((final_connections - initial_connections))
expected_increase=$((CYCLES * 30))  # Each cycle should leak 30 connections

if [ "$leak_detected" = true ]; then
    echo -e "${RED}✗ CONNECTION LEAK REPRODUCED!${NC}"
    echo -e "${RED}The 'Acquire operation took longer' error was triggered.${NC}"
    echo -e "${YELLOW}This confirms httpClient instances are not being closed on undeploy.${NC}"
elif [ $connection_increase -gt 100 ]; then
    echo -e "${YELLOW}⚠ LIKELY CONNECTION LEAK DETECTED${NC}"
    echo -e "${YELLOW}Connection increase: ${connection_increase} (expected leak: ~${expected_increase})${NC}"
    echo -e "${YELLOW}Each undeploy/redeploy cycle should leak 30 connections if bug exists.${NC}"
    echo -e "${YELLOW}Run more cycles or check system limits if timeout not yet triggered.${NC}"
else
    echo -e "${GREEN}✓ No obvious leak detected${NC}"
    echo -e "${YELLOW}Connection increase: ${connection_increase}${NC}"
    echo -e "${YELLOW}This could mean:${NC}"
    echo -e "${YELLOW}  1. The leak has been fixed${NC}"
    echo -e "${YELLOW}  2. Connections are being properly cleaned up${NC}"
    echo -e "${YELLOW}  3. System limits not yet reached${NC}"
    echo -e "${YELLOW}Try running with more cycles (CYCLES=30) to confirm.${NC}"
fi

echo ""
echo -e "${YELLOW}Check OpenSearch logs:${NC}"
echo "  grep -i 'Acquire operation took longer' <opensearch-log-path>"
echo "  grep -i 'Creating.*httpClient' <opensearch-log-path>"
echo ""
echo -e "${YELLOW}Check leaked connections:${NC}"
echo "  netstat -an | grep ESTABLISHED | grep ':443\\|:80'"
echo "  lsof -p \$(pgrep -f opensearch) | grep -i tcp | wc -l"
