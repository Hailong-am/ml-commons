# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

ML-Commons is an OpenSearch plugin that provides machine learning capabilities within OpenSearch clusters. It enables developers to leverage ML algorithms without managing ML infrastructure separately, supporting both built-in Java-based algorithms and external model serving.

## Core Architecture

### Module Structure
- **plugin**: Main plugin entry point (`MachineLearningPlugin.java`), REST handlers, transport actions, and cluster coordination
- **common**: Shared data models, transport actions, input/output classes, and exception types
- **ml-algorithms**: ML algorithm implementations (clustering, regression, anomaly detection, text embedding)
- **memory**: Conversational memory management for agentic workflows
- **search-processors**: RAG (Retrieval Augmented Generation) search pipeline processors
- **client**: Client library for interacting with ML Commons APIs
- **spi**: Service Provider Interface for extensibility

### Key Components

**MLEngine** (`ml-algorithms/src/main/java/org/opensearch/ml/engine/MLEngine.java`): Core engine that loads and executes ML algorithms. Uses `MLEngineClassLoader` to register function implementations.

**MLModelManager** (`plugin/src/main/java/org/opensearch/ml/model/MLModelManager.java`): Manages model lifecycle - deployment, undeployment, caching, and inference routing.

**MLTaskManager** (`plugin/src/main/java/org/opensearch/ml/task/MLTaskManager.java`): Handles ML task orchestration, tracking, and state management across the cluster.

**Transport Actions**: Follow OpenSearch's transport layer pattern. Actions are registered in `MachineLearningPlugin.getActions()` and implemented as `Transport*Action` classes in the `plugin/src/main/java/org/opensearch/ml/action/` directory.

**Tool System**: Extensible tool framework for agentic workflows. Tools are registered in `MachineLearningPlugin.createComponents()` and implement the `Tool` interface from `common/src/main/java/org/opensearch/ml/common/spi/tools/`.

## Common Development Commands

### Build and Test
```bash
./gradlew build                    # Build and test all modules
./gradlew spotlessApply            # Format code according to .eclipseformat.xml
./gradlew assemble                 # Build without running tests
```

### Running Locally
```bash
./gradlew run                      # Launch single-node cluster with plugin
./gradlew run --debug-jvm          # Launch with debugger on port 8000
```

### Integration Tests
```bash
./gradlew integTest                                    # Run all integration tests
./gradlew integTest -PnumNodes=3                       # Multi-node cluster
./gradlew integTest --tests="<class path>"             # Run specific test class
./gradlew integTest -Dtests.class="<class path>"       # Alternative syntax
./gradlew integTest -Dtests.method="<method name>"     # Run specific test method
```

### Docker Development
```bash
# Build plugin artifact
./gradlew assemble

# Start OpenSearch with plugin (artifact: plugin/build/distributions/opensearch-ml-*.zip)
docker-compose -f docs/docker/dev-docker-compose.yml up

# Access: http://localhost:5601 (username: admin, password: MyPassword123!)
```

### Testing Against Local Cluster
```bash
./gradlew integTest \
  -Dtests.rest.cluster=localhost:9200 \
  -Dtests.cluster=localhost:9200 \
  -Dtests.clustername="docker-cluster" \
  -Dhttps=true \
  -Duser=admin \
  -Dpassword=admin
```

## Adding New ML Functions

### Train/Predict Functions
1. Add function name to `common/src/main/java/org/opensearch/ml/common/FunctionName.java`
2. Create parameter class implementing `MLAlgoParams` in `common/src/main/java/org/opensearch/ml/common/input/parameter/`
   - Must have `@MLAlgoParameter` annotation
   - Must define `NamedXContentRegistry.Entry`
   - Register in `MachineLearningPlugin.getNamedXContent()`
3. Create output class extending `MLOutput` in `common/src/main/java/org/opensearch/ml/common/output/`
   - Add enum to `MLOutputType`
   - Must have `@MLAlgoOutput` annotation
4. Implement algorithm in `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/`
   - Implement `Trainable`, `Predictable`, or `TrainAndPredictable`
   - Must have `@Function` annotation with function name
5. Optionally register thread-safe instance in `MachineLearningPlugin.createComponents()` via `MLEngineClassLoader.register()`

### Execute-Only Functions
1. Add function name to `FunctionName.java`
2. Create input class implementing `Input` in `common/src/main/java/org/opensearch/ml/common/input/execute/`
   - Must have `@ExecuteInput` annotation
   - Must define `NamedXContentRegistry.Entry`
3. Create output class implementing `Output` in `common/src/main/java/org/opensearch/ml/common/output/execute/`
   - Must have `@ExecuteOutput` annotation
4. Implement algorithm in `ml-algorithms/src/main/java/org/opensearch/ml/engine/algorithms/`
   - Implement `Executable` interface
   - Must have `@Function` annotation (unless registered in step 5)
5. Optionally register in `MachineLearningPlugin.createComponents()`

Reference: `docs/how-to-add-new-function.md`

## Thread Pools

The plugin defines specialized thread pools (configured in `MachineLearningPlugin.getExecutorBuilders()`):
- `opensearch_ml_general`: General ML operations
- `opensearch_ml_train`: Training tasks
- `opensearch_ml_predict`: Inference tasks (local models)
- `opensearch_ml_predict_remote`: Remote model inference
- `opensearch_ml_predict_stream`: Streaming inference
- `opensearch_ml_execute`: Execute API tasks
- `opensearch_ml_execute_stream`: Streaming execute tasks
- `opensearch_ml_register`: Model registration
- `opensearch_ml_deploy`: Model deployment
- `opensearch_ml_ingest`: Batch ingestion
- `opensearch_ml_agentic_memory`: Agentic memory operations
- `opensearch_mcp_tools_sync`: MCP tool synchronization

## System Indices

ML-Commons manages these system indices:
- `.plugins-ml-agent`: Agent configurations
- `.plugins-ml-config`: ML configurations
- `.plugins-ml-connector`: External connector definitions
- `.plugins-ml-controller`: Model controllers
- `.plugins-ml-model-group`: Model group metadata
- `.plugins-ml-model`: Model metadata and content
- `.plugins-ml-task`: Task tracking
- `.plugins-ml-memory-meta`: Conversational memory metadata
- `.plugins-ml-memory-message`: Conversational memory messages
- `.plugins-ml-stop-words`: Stop words for text processing
- `.plugins-ml-jobs`: Scheduled job definitions
- `.plugins-ml-agentic-memory-*`: Agentic memory containers

## Code Style

- **File naming**: Use CamelCase (e.g., `MLModelGroup.java`)
- **Function size**: Keep functions under ~25 lines when possible
- **No commented code**: Remove unused code instead of commenting
- **Module structure**: All code must be in modules; avoid global definitions
- **Formatting**: Run `./gradlew spotlessApply` before committing
- **Imports**: Unused imports are automatically removed by Spotless

## API Endpoints

All ML APIs are under `/_plugins/_ml`:
- Training: `POST /_plugins/_ml/_train/<algorithm>`
- Prediction: `POST /_plugins/_ml/_predict/<algorithm>/<model_id>`
- Execute: `POST /_plugins/_ml/_execute/<algorithm>`
- Models: `/_plugins/_ml/models/<model_id>`
- Model Groups: `/_plugins/_ml/model_groups/<model_group_id>`
- Connectors: `/_plugins/_ml/connectors/<connector_id>`
- Agents: `/_plugins/_ml/agents/<agent_id>`
- Tasks: `/_plugins/_ml/tasks/<task_id>`

## Key Settings

Major cluster settings (defined in `MLCommonsSettings.java`):
- `plugins.ml_commons.only_run_on_ml_node`: Restrict ML to dedicated nodes
- `plugins.ml_commons.max_models_per_node`: Model deployment limit per node
- `plugins.ml_commons.model_access_control_enabled`: Enable model access control
- `plugins.ml_commons.connector_access_control_enabled`: Enable connector access control
- `plugins.ml_commons.trusted_connector_endpoints_regex`: Whitelist for external endpoints
- `plugins.ml_commons.agent_framework_enabled`: Enable agentic capabilities
- `plugins.ml_commons.memory_feature_enabled`: Enable conversational memory
- `plugins.ml_commons.rag_pipeline_feature_enabled`: Enable RAG search pipelines

## Debugging

Attach debugger to OpenSearch server:
```bash
./gradlew :run --debug-jvm
# Debugger listens on localhost:8000
```

Attach debugger to integration test runner:
```bash
./gradlew -Dtest.debug :integTest
# Debugger listens on localhost:5005
```

Logs are in `/build/cluster/run node0/opensearch-<version>/logs/`

## Additional Resources

- Model Serving Framework: https://opensearch.org/docs/latest/ml-commons-plugin/model-serving-framework/
- Model Access Control: https://github.com/opensearch-project/ml-commons/blob/2.x/docs/model_access_control.md
