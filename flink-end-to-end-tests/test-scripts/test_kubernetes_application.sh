#!/usr/bin/env bash
################################################################################
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
################################################################################

source "$(dirname "$0")"/common_kubernetes.sh

CLUSTER_ROLE_BINDING="flink-role-binding-default"
CLUSTER_ID="flink-native-k8s-application-1"
FLINK_IMAGE_NAME="test_kubernetes_application"
LOCAL_LOGS_PATH="${TEST_DATA_DIR}/log"
IMAGE_BUILD_RETRIES=3
IMAGE_BUILD_BACKOFF=2

function internal_cleanup {
    kubectl delete deployment ${CLUSTER_ID}
    kubectl delete clusterrolebinding ${CLUSTER_ROLE_BINDING}
}

start_kubernetes

if ! retry_times $IMAGE_BUILD_RETRIES $IMAGE_BUILD_BACKOFF "build_image ${FLINK_IMAGE_NAME} $(get_host_machine_address)"; then
	echo "ERROR: Could not build image. Aborting..."
	exit 1
fi

kubectl create clusterrolebinding ${CLUSTER_ROLE_BINDING} --clusterrole=edit --serviceaccount=default:default --namespace=default

mkdir -p "$LOCAL_LOGS_PATH"

# Set the memory and cpu smaller than default, so that the jobmanager and taskmanager pods could be allocated in minikube.
"$FLINK_DIR"/bin/flink run -t kubernetes-application \
    -Dkubernetes.cluster-id=${CLUSTER_ID} \
    -Dkubernetes.container.image.ref=${FLINK_IMAGE_NAME} \
    -Djobmanager.memory.process.size=1088m \
    -Dkubernetes.jobmanager.cpu=0.5 \
    -Dkubernetes.taskmanager.cpu=0.5 \
    -Dkubernetes.rest-service.exposed.type=NodePort \
    local:///opt/flink/examples/batch/WordCount.jar

kubectl wait --for=condition=Available --timeout=30s deploy/${CLUSTER_ID} || exit 1
jm_pod_name=$(kubectl get pods --selector="app=${CLUSTER_ID},component=jobmanager" -o jsonpath='{..metadata.name}')
wait_rest_endpoint_up_k8s $jm_pod_name

# The Flink cluster will be destroyed immediately once the job finished or failed. So we check jobmanager logs
# instead of checking the result
kubectl logs -f $jm_pod_name >$LOCAL_LOGS_PATH/jobmanager.log
grep -E "Job [A-Za-z0-9]+ reached terminal state FINISHED" $LOCAL_LOGS_PATH/jobmanager.log
