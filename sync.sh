#!/bin/bash

cd /home/gauges/gauges || exit 1

git add .
git commit -m "update"
git push