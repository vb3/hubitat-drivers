#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

container run --rm \
  --network none \
  --read-only \
  --tmpfs /tmp \
  --cpus 2 \
  --memory 1G \
  --mount "type=bind,source=$root/Drivers,target=/src,readonly" \
  --mount "type=bind,source=$root/tests,target=/tests,readonly" \
  groovy:3.0.25-jdk21 \
  groovy /tests/driver-regression.groovy /src/universal/universal-zwave-dimmer.groovy
