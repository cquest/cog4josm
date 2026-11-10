#!/bin/sh
# Telecharge l'API JOSM compilee la plus recente (josm-tested.jar) dans lib/.
set -e
mkdir -p lib
curl -sL -o lib/josm-tested.jar https://josm.openstreetmap.de/download/josm-tested.jar
ls -l lib/josm-tested.jar
