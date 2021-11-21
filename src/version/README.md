# version_util

Description: Simple version utility shell.

## Goal

It is tiresome to updating libraries in Dockerfile.
To automate it, we need a script to check latest version of those libraries.

This script's goal is to process version for automation.

## How to use

Copy or source version_util.sh and use function.

As is tested, assume we have Dockerfile like below,

```Dockerfile
ARG JAVA_VERSION="17"
FROM openjdk:${JAVA_VERSION}-jdk-alpine

ARG KUBECTL_VERSION="v1.19.4"
RUN curl -LO https://storage.googleapis.com/kubernetes-release/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl
```

we want to extract JAVA_VERSION, KUBECTL_VERSION and check whether new version exists,

```bash
source version_util.sh

java_ver=$(get_library_ver_dockerfile JAVA)
# this get version string -> 17
inc_java_ver=$(increment_simple_version ${java_ver})
# this get 18, so use ${inc_java_ver} for checking if exists

kubectl_ver=$(get_library_ver_dockerfile KUBECTL)
# this get version string -> v1.19.4
inc_patch_kubectl_ver=$(increment_semver_patch ${kubectl_ver})
# this get v1.19.5, so use ${inc_patch_kubectl_ver} for checking if exists
```

### Example project using version_util

- [go-zen-chu/docker-plantuml: Docker image of plantuml cli](https://github.com/go-zen-chu/docker-plantuml)
  - [update.sh uses version_util to check new version exists](https://github.com/go-zen-chu/docker-plantuml/blob/master/make/update.sh)
