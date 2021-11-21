#!/usr/bin/env bash

set -eux

source ./version_util.sh

java_ver=$(get_library_ver_dockerfile "JAVA" "./Dockerfile.test")
[ "${java_ver}" == "17" ]

inc_java_ver=$(increment_simple_version "${java_ver}")
[ "${inc_java_ver}" == "18" ]

kubectl_ver=$(get_library_ver_dockerfile "KUBECTL" "./Dockerfile.test")
[ "${kubectl_ver}" == "v1.19.4" ]

inc_major_kubectl_ver=$(increment_semver_major "${kubectl_ver}")
[ "${inc_major_kubectl_ver}" == "v2.19.4" ]

inc_minor_kubectl_ver=$(increment_semver_minor "${kubectl_ver}")
[ "${inc_minor_kubectl_ver}" == "v1.20.4" ]

inc_patch_kubectl_ver=$(increment_semver_patch "${kubectl_ver}")
[ "${inc_patch_kubectl_ver}" == "v1.19.5" ]

exit 0
