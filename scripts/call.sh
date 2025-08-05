#!/usr/bin/env bash
set -e

echo "Starting call to $TARGET_PHONE_NUMBER"

curl -X POST -H "Content-Type: application/json" \
  --data @- \
  localhost:8080/api/calls <<EOF
{
  "phoneNumber": "$TARGET_PHONE_NUMBER",
  "ringingTimeoutSeconds": 15,
  "callTimeLimitSeconds": 30
}
EOF

