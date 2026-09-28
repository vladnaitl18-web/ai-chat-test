#!/bin/sh
set -eu
cd "$(dirname "$0")/../server"
exec node --env-file-if-exists=.env server.mjs
