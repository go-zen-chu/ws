# ws (work station)

[![Actions Status](https://github.com/go-zen-chu/ws/workflows/push-image/badge.svg)](https://github.com/go-zen-chu/ws/actions)
[![Actions Status](https://github.com/go-zen-chu/ws/workflows/ci/badge.svg)](https://github.com/go-zen-chu/ws/actions)
[![Docker Pulls](https://img.shields.io/docker/pulls/amasuda/ws.svg?style=for-the-badge)](https://hub.docker.com/r/amasuda/ws/)
[![Docker Automated build](https://img.shields.io/docker/automated/amasuda/ws.svg?style=for-the-badge)](https://hub.docker.com/r/amasuda/ws/)

My personal work station image built on archlinux.

## Goal

- Create an immutable development environment

## Usage

Replace `docker` to `lima nerdctl` if you use lima & nerdctl on MacOS.

### install

```shell
docker pull amasuda/ws:latest
```

### run

```shell
# simple
docker run -it amasuda/ws:latest
# with TZ and writing host data
docker run -it -e TZ=Asia/Tokyo -v $HOME:/mnt/home amasuda/ws:latest
```

## LICENSE

MIT : [LICENSE](./LICENSE)
